package com.ezeebit.wallet.deposit;

import com.ezeebit.wallet.common.Amounts;
import com.ezeebit.wallet.common.ApiException;
import com.ezeebit.wallet.common.CurrencyCode;
import com.ezeebit.wallet.common.RequestHash;
import com.ezeebit.wallet.ledger.AccountPurpose;
import com.ezeebit.wallet.ledger.AccountRef;
import com.ezeebit.wallet.ledger.JournalEntryType;
import com.ezeebit.wallet.ledger.JournalLine;
import com.ezeebit.wallet.ledger.JournalRequest;
import com.ezeebit.wallet.ledger.LedgerService;
import com.ezeebit.wallet.merchant.MerchantService;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 1: money arriving for a merchant.
 *
 * <p>Deposits are reported by upstream systems (bank-statement ingestion, the on-chain
 * confirmation flow from Task 4). Those systems may report the same arrival more than once, so
 * {@code (source, externalReference)} is the idempotency key: the first report credits the merchant,
 * every repeat returns the original deposit and moves no money.
 */
@Service
public class DepositService {

    public record DepositResult(Deposit deposit, boolean replayed) {
    }

    private final DepositRepository deposits;
    private final LedgerService ledger;
    private final MerchantService merchants;
    private final TransactionTemplate tx;
    private final Clock clock;

    public DepositService(DepositRepository deposits, LedgerService ledger, MerchantService merchants,
                          TransactionTemplate tx, Clock clock) {
        this.deposits = deposits;
        this.ledger = ledger;
        this.merchants = merchants;
        this.tx = tx;
        this.clock = clock;
    }

    public DepositResult record(long merchantId, String sourceRaw, String externalReference,
                                String currencyRaw, BigDecimal amountRaw) {
        merchants.require(merchantId);
        CurrencyCode currency = CurrencyCode.parse(currencyRaw);
        BigDecimal amount = Amounts.requireValidPositive(amountRaw, currency);
        String source = sourceRaw.trim().toUpperCase(Locale.ROOT);
        String hash = RequestHash.of(merchantId, source, externalReference, currency, amount.toPlainString());

        Optional<Deposit> existing = deposits.findBySourceAndExternalReference(source, externalReference);
        if (existing.isPresent()) {
            return replay(existing.get(), hash);
        }
        try {
            Deposit created = tx.execute(status -> create(merchantId, source, externalReference, currency, amount, hash));
            return new DepositResult(created, false);
        } catch (DataAccessException race) {
            // Unique-key violation, lock timeout or deadlock with a concurrent identical request. Our
            // transaction rolled back, so nothing moved; return the winner's result if it has committed.
            return deposits.findBySourceAndExternalReference(source, externalReference)
                    .map(d -> replay(d, hash))
                    .orElseThrow(() -> ApiException.conflict("CONCURRENT_REQUEST",
                            "A concurrent request for this deposit is in progress; retry"));
        }
    }

    private Deposit create(long merchantId, String source, String externalReference,
                           CurrencyCode currency, BigDecimal amount, String hash) {
        // Money that has actually arrived must always be creditable, so open the currency if needed.
        ledger.openMerchantCurrency(merchantId, currency);
        String depositId = UUID.randomUUID().toString();
        long journalId = ledger.post(new JournalRequest(
                JournalEntryType.DEPOSIT, "DEPOSIT", depositId,
                "Deposit via " + source + " ref " + externalReference,
                List.of(
                        JournalLine.debit(AccountRef.platform(currency, AccountPurpose.DEPOSIT_CLEARING), amount),
                        JournalLine.credit(AccountRef.merchant(merchantId, currency, AccountPurpose.AVAILABLE), amount))));
        // The unique (source, external_reference) constraint is the final word on duplicates:
        // if another transaction recorded this deposit first, this flush fails and the ledger
        // posting above rolls back with it.
        return deposits.saveAndFlush(new Deposit(depositId, merchantId, source, externalReference, currency,
                amount, hash, journalId, clock.instant()));
    }

    private DepositResult replay(Deposit deposit, String hash) {
        if (!deposit.getRequestHash().equals(hash)) {
            throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED",
                    "Deposit reference " + deposit.getExternalReference() + " was already used with different details");
        }
        return new DepositResult(deposit, true);
    }
}
