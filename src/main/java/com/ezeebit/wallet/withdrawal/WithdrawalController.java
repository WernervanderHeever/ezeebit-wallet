package com.ezeebit.wallet.withdrawal;

import com.ezeebit.wallet.common.Amounts;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;

@RestController
public class WithdrawalController {

    private final WithdrawalService service;

    public WithdrawalController(WithdrawalService service) {
        this.service = service;
    }

    public record WithdrawalRequest(@NotBlank String currency,
                                    @NotNull BigDecimal amount,
                                    @NotBlank String destinationType,
                                    @NotBlank @Size(max = 256) String destination) {
    }

    public record WithdrawalResponse(String withdrawalId, String status, String currency, String amount,
                                     String destinationType, String destination, String providerReference,
                                     String failureReason, Instant createdAt, Instant updatedAt) {
        static WithdrawalResponse of(Withdrawal w) {
            return new WithdrawalResponse(w.getId(), w.getStatus().name(), w.getCurrency().name(),
                    Amounts.format(w.getAmount(), w.getCurrency()), w.getDestinationType().name(), w.getDestination(),
                    w.getProviderReference(), w.getFailureReason(), w.getCreatedAt(), w.getUpdatedAt());
        }
    }

    public record CallbackRequest(@NotBlank String withdrawalId,
                                  String providerReference,
                                  String eventId,
                                  @NotNull PayoutResult status,
                                  String reason) {
    }

    public record CallbackResponse(String withdrawalId, String outcome, String withdrawalStatus) {
    }

    /**
     * 202 Accepted for a new withdrawal (the payout completes asynchronously), 200 when the
     * Idempotency-Key matches a withdrawal we already accepted.
     */
    @PostMapping("/api/v1/merchants/{merchantId}/withdrawals")
    public ResponseEntity<WithdrawalResponse> request(@PathVariable long merchantId,
                                                      @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                      @Valid @RequestBody WithdrawalRequest request) {
        WithdrawalService.WithdrawalResult result = service.request(merchantId, idempotencyKey, request.currency(),
                request.amount(), request.destinationType(), request.destination());
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .body(WithdrawalResponse.of(result.withdrawal()));
    }

    @GetMapping("/api/v1/merchants/{merchantId}/withdrawals/{withdrawalId}")
    public WithdrawalResponse get(@PathVariable long merchantId, @PathVariable String withdrawalId) {
        return WithdrawalResponse.of(service.get(merchantId, withdrawalId));
    }

    /**
     * Webhook from the payout rail. Always 200 for a known withdrawal (even duplicates/conflicts) so
     * the rail stops retrying; conflicts are flagged for manual review. In production this endpoint
     * would verify the rail's HMAC signature before doing anything.
     */
    @PostMapping("/api/v1/payout-callbacks")
    public CallbackResponse callback(@Valid @RequestBody CallbackRequest request) {
        PayoutCallback.Outcome outcome = service.applyCallback(new WithdrawalService.CallbackCommand(
                request.withdrawalId(), request.providerReference(), request.eventId(), request.status(), request.reason()));
        Withdrawal w = service.getById(request.withdrawalId());
        return new CallbackResponse(w.getId(), outcome.name(), w.getStatus().name());
    }
}
