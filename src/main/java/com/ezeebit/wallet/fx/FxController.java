package com.ezeebit.wallet.fx;

import com.ezeebit.wallet.common.Amounts;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/fx")
public class FxController {

    private final ConversionService service;
    private final Clock clock;

    public FxController(ConversionService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    /** {@code amount} is the amount of {@code fromCurrency} the merchant wants to sell. */
    public record QuoteRequest(@NotBlank String fromCurrency, @NotBlank String toCurrency, @NotNull BigDecimal amount) {
    }

    public record ConversionRequest(@NotBlank String quoteId) {
    }

    /** {@code price} is always fiat per 1 stablecoin, e.g. ZAR per USDT. */
    public record QuoteResponse(String quoteId, String fromCurrency, String toCurrency, String fromAmount,
                                String toAmount, String price, String status, Instant expiresAt) {
    }

    public record ConversionResponse(String conversionId, String quoteId, String fromCurrency, String toCurrency,
                                     String fromAmount, String toAmount, String price, long journalEntryId,
                                     Instant executedAt) {
        static ConversionResponse of(FxConversion c) {
            return new ConversionResponse(c.getId(), c.getQuoteId(), c.getFromCurrency().name(), c.getToCurrency().name(),
                    Amounts.format(c.getFromAmount(), c.getFromCurrency()), Amounts.format(c.getToAmount(), c.getToCurrency()),
                    c.getClientPrice().stripTrailingZeros().toPlainString(), c.getJournalEntryId(), c.getCreatedAt());
        }
    }

    @PostMapping("/quotes")
    @ResponseStatus(HttpStatus.CREATED)
    public QuoteResponse quote(@PathVariable long merchantId, @Valid @RequestBody QuoteRequest request) {
        return toResponse(service.createQuote(merchantId, request.fromCurrency(), request.toCurrency(), request.amount()));
    }

    @GetMapping("/quotes/{quoteId}")
    public QuoteResponse getQuote(@PathVariable long merchantId, @PathVariable String quoteId) {
        return toResponse(service.getQuote(merchantId, quoteId));
    }

    /** 201 for a new conversion, 200 when replaying a quote that was already executed. */
    @PostMapping("/conversions")
    public ResponseEntity<ConversionResponse> convert(@PathVariable long merchantId,
                                                      @Valid @RequestBody ConversionRequest request) {
        ConversionService.ConversionResult result = service.execute(merchantId, request.quoteId());
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(ConversionResponse.of(result.conversion()));
    }

    @GetMapping("/conversions/{conversionId}")
    public ConversionResponse getConversion(@PathVariable long merchantId, @PathVariable String conversionId) {
        return ConversionResponse.of(service.getConversion(merchantId, conversionId));
    }

    private QuoteResponse toResponse(FxQuote q) {
        String status = q.getStatus() == FxQuote.Status.OPEN && q.isExpiredAt(clock.instant()) ? "EXPIRED" : q.getStatus().name();
        return new QuoteResponse(q.getId(), q.getFromCurrency().name(), q.getToCurrency().name(),
                Amounts.format(q.getFromAmount(), q.getFromCurrency()), Amounts.format(q.getToAmount(), q.getToCurrency()),
                q.getClientPrice().stripTrailingZeros().toPlainString(), status, q.getExpiresAt());
    }
}
