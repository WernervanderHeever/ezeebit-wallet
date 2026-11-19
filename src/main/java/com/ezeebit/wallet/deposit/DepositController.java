package com.ezeebit.wallet.deposit;

import com.ezeebit.wallet.common.Amounts;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Internal endpoint: called by the systems that observe money arriving (bank ingestion, on-chain
 * confirmation), not by the merchant dashboard.
 */
@RestController
public class DepositController {

    private final DepositService service;

    public DepositController(DepositService service) {
        this.service = service;
    }

    public record DepositRequest(@NotBlank @Pattern(regexp = "[A-Za-z_]{2,32}") String source,
                                 @NotBlank @Size(max = 128) String externalReference,
                                 @NotBlank String currency,
                                 @NotNull BigDecimal amount) {
    }

    public record DepositResponse(String id, long merchantId, String source, String externalReference,
                                  String currency, String amount, long journalEntryId, Instant createdAt) {
        static DepositResponse of(Deposit d) {
            return new DepositResponse(d.getId(), d.getMerchantId(), d.getSource(), d.getExternalReference(),
                    d.getCurrency().name(), Amounts.format(d.getAmount(), d.getCurrency()),
                    d.getJournalEntryId(), d.getCreatedAt());
        }
    }

    /** 201 when the deposit is new, 200 when it is a replay of one already recorded. */
    @PostMapping("/api/v1/merchants/{merchantId}/deposits")
    public ResponseEntity<DepositResponse> deposit(@PathVariable long merchantId,
                                                   @Valid @RequestBody DepositRequest request) {
        DepositService.DepositResult result = service.record(merchantId, request.source(),
                request.externalReference(), request.currency(), request.amount());
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(DepositResponse.of(result.deposit()));
    }
}
