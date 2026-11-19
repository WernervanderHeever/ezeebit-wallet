package com.ezeebit.wallet.fx;

import com.ezeebit.wallet.common.Amounts;
import com.ezeebit.wallet.common.ApiException;
import com.ezeebit.wallet.common.CurrencyCode;
import com.ezeebit.wallet.common.WalletProperties;
import com.ezeebit.wallet.ledger.AccountPurpose;
import com.ezeebit.wallet.ledger.AccountRef;
import com.ezeebit.wallet.ledger.JournalEntryType;
import com.ezeebit.wallet.ledger.JournalLine;
import com.ezeebit.wallet.ledger.JournalRequest;
import com.ezeebit.wallet.ledger.LedgerService;
import com.ezeebit.wallet.merchant.MerchantService;
import com.ezeebit.wallet.outbox.OutboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Task 2: converting between a fiat currency and a stablecoin.
 *
 * <p>Two steps. {@link #createQuote} fetches a (validated) market rate, applies the platform spread
 * and returns a firm price that is honoured for a short TTL. {@link #execute} turns that exact quote
 * into ledger movements, at most once. The merchant always gets exactly the quoted amount; the
 * platform protects itself with the spread, the short TTL, a "last look" that refuses quotes the
 * market has moved through, and by publishing every execution for hedging.
 */
@Service
public class ConversionService {

    public static final String HEDGE_MESSAGE = "FX_HEDGE";

    private static final Logger log = LoggerFactory.getLogger(ConversionService.class);

    public record ConversionResult(FxConversion conversion, boolean replayed) {
    }

    private final FxQuoteRepository quotes;
    private final FxConversionRepository conversions;
    private final RateService rates;
    private final LedgerService ledger;
    private final MerchantService merchants;
    private final OutboxService outbox;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final WalletProperties.Fx config;

    public ConversionService(FxQuoteRepository quotes, FxConversionRepository conversions, RateService rates,
                             LedgerService ledger, MerchantService merchants, OutboxService outbox,
                             TransactionTemplate tx, Clock clock, WalletProperties properties) {
        this.quotes = quotes;
        this.conversions = conversions;
        this.rates = rates;
        this.ledger = ledger;
        this.merchants = merchants;
        this.outbox = outbox;
        this.tx = tx;
        this.clock = clock;
        this.config = properties.fx();
    }

    // ------------------------------------------------------------------ quote

    public FxQuote createQuote(long merchantId, String fromRaw, String toRaw, BigDecimal amountRaw) {
        merchants.require(merchantId);
        CurrencyCode from = CurrencyCode.parse(fromRaw);
        CurrencyCode to = CurrencyCode.parse(toRaw);
        FxPricing.requireSupportedPair(from, to);
        BigDecimal fromAmount = Amounts.requireValidPositive(amountRaw, from);

        // Slow network call: deliberately outside any transaction.
        RateSnapshot rate = rates.currentRate(FxPricing.stablecoinOf(from, to), FxPricing.fiatOf(from, to));
        FxPricing.Priced priced = FxPricing.price(from, to, fromAmount, rate.price(), config.spreadBps());

        Instant now = clock.instant();
        FxQuote quote = new FxQuote(UUID.randomUUID().toString(), merchantId, from, to, fromAmount,
                priced.toAmount(), rate.price(), priced.clientPrice(), config.spreadBps(), rate.source(),
                rate.asOf(), now.plus(config.quoteTtl()), now);
        return quotes.save(quote);
    }

    public FxQuote getQuote(long merchantId, String quoteId) {
        return quotes.findById(quoteId)
                .filter(q -> q.getMerchantId() == merchantId)
                .orElseThrow(() -> ApiException.notFound("Quote", quoteId));
    }

    // ------------------------------------------------------------------ execute

    /**
     * Executes a quote. Idempotent on the quote id: a retried request returns the original
     * conversion and moves no further money.
     */
    public ConversionResult execute(long merchantId, String quoteId) {
        FxQuote quote = getQuote(merchantId, quoteId);
        if (quote.getStatus() == FxQuote.Status.EXECUTED) {
            return replay(quoteId);
        }
        if (quote.isExpiredAt(clock.instant())) {
            throw ApiException.conflict("QUOTE_EXPIRED", "Quote expired at " + quote.getExpiresAt() + "; request a new quote");
        }
        lastLook(quote);
        try {
            return tx.execute(status -> executeLocked(merchantId, quoteId));
        } catch (DataAccessException race) {
            // Unique-key violation, lock timeout or deadlock with a concurrent identical request. Our
            // transaction rolled back, so nothing moved; return the winner's result if it has committed.
            log.warn("Concurrent/failed write for quote {}: {}", quoteId, race.toString());
            if (conversions.findByQuoteId(quoteId).isPresent()) {
                return replay(quoteId);
            }
            throw ApiException.conflict("CONCURRENT_REQUEST", "A concurrent request for this quote is in progress; retry");
        }
    }

    private ConversionResult executeLocked(long merchantId, String quoteId) {
        FxQuote quote = quotes.findForUpdate(quoteId).orElseThrow(() -> ApiException.notFound("Quote", quoteId));
        if (quote.getStatus() == FxQuote.Status.EXECUTED) {
            // Lost the race to a concurrent request for the same quote: return its result.
            return new ConversionResult(conversions.findByQuoteId(quoteId).orElseThrow(), true);
        }
        Instant now = clock.instant();
        if (quote.isExpiredAt(now)) {
            throw ApiException.conflict("QUOTE_EXPIRED", "Quote expired at " + quote.getExpiresAt() + "; request a new quote");
        }
        CurrencyCode from = quote.getFromCurrency();
        CurrencyCode to = quote.getToCurrency();
        BigDecimal fromAmount = Amounts.normalise(quote.getFromAmount(), from);
        BigDecimal toAmount = Amounts.normalise(quote.getToAmount(), to);

        ledger.openMerchantCurrency(merchantId, to);
        String conversionId = UUID.randomUUID().toString();
        // Two balanced legs, one per currency, both through the platform FX book.
        // If the merchant does not hold fromAmount, the first line fails and nothing is written.
        long journalId = ledger.post(new JournalRequest(
                JournalEntryType.FX_CONVERSION, "FX_CONVERSION", conversionId,
                "Convert " + fromAmount.toPlainString() + " " + from + " to " + toAmount.toPlainString() + " " + to
                        + " @ " + quote.getClientPrice().stripTrailingZeros().toPlainString() + " (quote " + quoteId + ")",
                List.of(
                        JournalLine.debit(AccountRef.merchant(merchantId, from, AccountPurpose.AVAILABLE), fromAmount),
                        JournalLine.credit(AccountRef.platform(from, AccountPurpose.FX_LIQUIDITY), fromAmount),
                        JournalLine.debit(AccountRef.platform(to, AccountPurpose.FX_LIQUIDITY), toAmount),
                        JournalLine.credit(AccountRef.merchant(merchantId, to, AccountPurpose.AVAILABLE), toAmount))));
        quote.markExecuted();
        FxConversion conversion = conversions.saveAndFlush(new FxConversion(conversionId, quote, journalId, now));
        outbox.enqueue(HEDGE_MESSAGE, conversionId, null);
        return new ConversionResult(conversion, false);
    }

    /**
     * Refuses to execute if the market has moved against the platform by more than the configured
     * tolerance since the quote was issued. If the rate service is down we honour the quote (the
     * short TTL bounds the risk) unless configured otherwise.
     */
    private void lastLook(FxQuote quote) {
        CurrencyCode from = quote.getFromCurrency();
        CurrencyCode to = quote.getToCurrency();
        RateSnapshot fresh;
        try {
            fresh = rates.currentRate(FxPricing.stablecoinOf(from, to), FxPricing.fiatOf(from, to));
        } catch (ApiException e) {
            if (config.honourQuoteWhenRateUnavailable()) {
                log.warn("Last-look rate unavailable for quote {} ({}); honouring quote within its TTL", quote.getId(), e.code());
                return;
            }
            throw e;
        }
        long adverseBps = FxPricing.adverseMoveBps(from, quote.getMidPrice(), fresh.price());
        if (adverseBps > config.maxAdverseMoveBps()) {
            log.info("Quote {} refused at last look: market moved {}bps against the platform", quote.getId(), adverseBps);
            throw ApiException.conflict("QUOTE_REPRICE_REQUIRED",
                    "The market moved since this quote was issued; request a new quote");
        }
    }

    public FxConversion getConversion(long merchantId, String conversionId) {
        return conversions.findById(conversionId)
                .filter(c -> c.getMerchantId() == merchantId)
                .orElseThrow(() -> ApiException.notFound("Conversion", conversionId));
    }

    private ConversionResult replay(String quoteId) {
        return new ConversionResult(conversions.findByQuoteId(quoteId).orElseThrow(), true);
    }
}
