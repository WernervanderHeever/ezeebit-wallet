package com.ezeebit.wallet;

import com.ezeebit.wallet.withdrawal.PayoutCallback;
import com.ezeebit.wallet.withdrawal.PayoutCallbackRepository;
import com.ezeebit.wallet.withdrawal.PayoutRail;
import com.ezeebit.wallet.withdrawal.PayoutRailUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Task 3: withdrawals - reserve, submit, settle or release. */
class WithdrawalTest extends IntegrationTestBase {

    @Autowired
    PayoutCallbackRepository callbacks;

    private ResultActions withdraw(long m, String key, String currency, String amount, String type, String destination)
            throws Exception {
        return mvc.perform(post("/api/v1/merchants/" + m + "/withdrawals")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("currency", currency, "amount", amount,
                        "destinationType", type, "destination", destination))));
    }

    private String withdrawZar(long m, String key, String amount) throws Exception {
        return readJson(withdraw(m, key, "ZAR", amount, "BANK_ACCOUNT", "FNB 62000000001")
                .andExpect(status().isAccepted())).get("withdrawalId").asText();
    }

    private JsonNode callback(String withdrawalId, String providerRef, String result) throws Exception {
        return readJson(postJson("/api/v1/payout-callbacks", Map.of("withdrawalId", withdrawalId,
                "providerReference", providerRef, "eventId", "evt-" + System.nanoTime(), "status", result))
                .andExpect(status().isOk()));
    }

    private String withdrawalStatus(long m, String withdrawalId) throws Exception {
        return readJson(mvc.perform(get("/api/v1/merchants/" + m + "/withdrawals/" + withdrawalId))
                .andExpect(status().isOk())).get("status").asText();
    }

    @Test
    void successfulPayoutHoldsThenSettles() throws Exception {
        long m = createMerchant("ZAR");
        deposit(m, "ZAR", "500.00");
        when(payoutRail.submit(any())).thenReturn(PayoutRail.SubmitResult.accepted("RAIL-1"));

        String id = withdrawZar(m, "k-1", "200.00");
        assertThat(available(m, "ZAR")).isEqualTo("300.00");
        assertThat(held(m, "ZAR")).isEqualTo("200.00");
        assertThat(withdrawalStatus(m, id)).isEqualTo("PENDING_SUBMISSION");

        dispatcher.dispatchDue();
        ArgumentCaptor<PayoutRail.PayoutInstruction> sent = ArgumentCaptor.forClass(PayoutRail.PayoutInstruction.class);
        verify(payoutRail).submit(sent.capture());
        assertThat(sent.getValue().reference()).isEqualTo(id); // our id is the rail's idempotency reference
        assertThat(withdrawalStatus(m, id)).isEqualTo("SUBMITTED");

        JsonNode cb = callback(id, "RAIL-1", "SUCCEEDED");
        assertThat(cb.get("outcome").asText()).isEqualTo("APPLIED");
        assertThat(withdrawalStatus(m, id)).isEqualTo("COMPLETED");
        assertThat(available(m, "ZAR")).isEqualTo("300.00");
        assertThat(held(m, "ZAR")).isEqualTo("0.00");
        assertLedgerHealthy();
    }

    @Test
    void failedPayoutReturnsTheFunds() throws Exception {
        long m = createMerchant("ZAR");
        deposit(m, "ZAR", "500.00");
        when(payoutRail.submit(any())).thenReturn(PayoutRail.SubmitResult.accepted("RAIL-2"));
        String id = withdrawZar(m, "k-1", "200.00");
        dispatcher.dispatchDue();

        callback(id, "RAIL-2", "FAILED");
        assertThat(withdrawalStatus(m, id)).isEqualTo("FAILED");
        assertThat(available(m, "ZAR")).isEqualTo("500.00");
        assertThat(held(m, "ZAR")).isEqualTo("0.00");
        assertLedgerHealthy();
    }

    @Test
    void retriedRequestWithSameKeyCreatesOneWithdrawal() throws Exception {
        long m = createMerchant("ZAR");
        deposit(m, "ZAR", "500.00");
        String first = withdrawZar(m, "dup-key", "100.00");
        JsonNode replay = readJson(withdraw(m, "dup-key", "ZAR", "100.00", "BANK_ACCOUNT", "FNB 62000000001")
                .andExpect(status().isOk()));

        assertThat(replay.get("withdrawalId").asText()).isEqualTo(first);
        assertThat(available(m, "ZAR")).isEqualTo("400.00");
        assertThat(held(m, "ZAR")).isEqualTo("100.00");
    }

    @Test
    void sameKeyForADifferentWithdrawalIsRejected() throws Exception {
        long m = createMerchant("ZAR");
        deposit(m, "ZAR", "500.00");
        withdrawZar(m, "key-x", "100.00");
        withdraw(m, "key-x", "ZAR", "250.00", "BANK_ACCOUNT", "FNB 62000000001")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertThat(available(m, "ZAR")).isEqualTo("400.00");
    }

    @Test
    void cannotWithdrawMoreThanAvailable() throws Exception {
        long m = createMerchant("ZAR");
        deposit(m, "ZAR", "100.00");
        withdraw(m, "k", "ZAR", "100.01", "BANK_ACCOUNT", "FNB 1")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
        assertThat(available(m, "ZAR")).isEqualTo("100.00");
        assertThat(held(m, "ZAR")).isEqualTo("0.00");
    }

    @Test
    void heldFundsCannotBeSpentTwice() throws Exception {
        long m = createMerchant("ZAR");
        deposit(m, "USDT", "100");
        readJson(withdraw(m, "a", "USDT", "70", "BLOCKCHAIN_ADDRESS", "TXyz123").andExpect(status().isAccepted()));
        withdraw(m, "b", "USDT", "70", "BLOCKCHAIN_ADDRESS", "TXyz123")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    void destinationMustMatchCurrencyKind() throws Exception {
        long m = createMerchant("ZAR");
        deposit(m, "USDT", "100");
        withdraw(m, "k", "USDT", "10", "BANK_ACCOUNT", "FNB 1")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DESTINATION_MISMATCH"));
    }

    @Test
    void missingIdempotencyKeyIsRejected() throws Exception {
        long m = createMerchant("ZAR");
        mvc.perform(post("/api/v1/merchants/" + m + "/withdrawals").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"ZAR\",\"amount\":\"1\",\"destinationType\":\"BANK_ACCOUNT\",\"destination\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void duplicateAndContradictoryCallbacksNeverMoveMoneyTwice() throws Exception {
        long m = createMerchant("ZAR");
        deposit(m, "ZAR", "500.00");
        when(payoutRail.submit(any())).thenReturn(PayoutRail.SubmitResult.accepted("RAIL-3"));
        String id = withdrawZar(m, "k", "200.00");
        dispatcher.dispatchDue();

        assertThat(callback(id, "RAIL-3", "SUCCEEDED").get("outcome").asText()).isEqualTo("APPLIED");
        assertThat(callback(id, "RAIL-3", "SUCCEEDED").get("outcome").asText()).isEqualTo("DUPLICATE");
        assertThat(callback(id, "RAIL-3", "FAILED").get("outcome").asText()).isEqualTo("CONFLICT");

        assertThat(withdrawalStatus(m, id)).isEqualTo("COMPLETED");
        assertThat(available(m, "ZAR")).isEqualTo("300.00");
        assertThat(held(m, "ZAR")).isEqualTo("0.00");
        assertThat(callbacks.findByWithdrawalIdOrderByIdAsc(id))
                .extracting(PayoutCallback::getOutcome)
                .containsExactly(PayoutCallback.Outcome.APPLIED, PayoutCallback.Outcome.DUPLICATE,
                        PayoutCallback.Outcome.CONFLICT);
        assertLedgerHealthy();
    }

    @Test
    void ambiguousSubmissionFailureIsRetriedWithTheSameReference() throws Exception {
        long m = createMerchant("ZAR");
        deposit(m, "ZAR", "500.00");
        when(payoutRail.submit(any()))
                .thenThrow(new PayoutRailUnavailableException("timeout"))
                .thenReturn(PayoutRail.SubmitResult.accepted("RAIL-4"));
        String id = withdrawZar(m, "k", "200.00");

        dispatcher.dispatchDue();
        assertThat(withdrawalStatus(m, id)).isEqualTo("PENDING_SUBMISSION");
        assertThat(held(m, "ZAR")).isEqualTo("200.00"); // never released on an ambiguous failure

        dispatcher.dispatchDue(); // backoff not elapsed yet: nothing happens
        verify(payoutRail, times(1)).submit(any());

        clock.advance(Duration.ofSeconds(5));
        dispatcher.dispatchDue();
        ArgumentCaptor<PayoutRail.PayoutInstruction> sent = ArgumentCaptor.forClass(PayoutRail.PayoutInstruction.class);
        verify(payoutRail, times(2)).submit(sent.capture());
        assertThat(sent.getAllValues()).extracting(PayoutRail.PayoutInstruction::reference).containsOnly(id);
        assertThat(withdrawalStatus(m, id)).isEqualTo("SUBMITTED");
    }

    @Test
    void rejectionAtSubmissionReleasesTheFunds() throws Exception {
        long m = createMerchant("ZAR");
        deposit(m, "ZAR", "500.00");
        when(payoutRail.submit(any())).thenReturn(PayoutRail.SubmitResult.rejected("Invalid account number"));
        String id = withdrawZar(m, "k", "200.00");
        dispatcher.dispatchDue();

        assertThat(withdrawalStatus(m, id)).isEqualTo("FAILED");
        assertThat(available(m, "ZAR")).isEqualTo("500.00");
        assertLedgerHealthy();
    }

    @Test
    void callbackThatArrivesBeforeOurSubmissionAckIsHandled() throws Exception {
        long m = createMerchant("ZAR");
        deposit(m, "ZAR", "500.00");
        String id = withdrawZar(m, "k", "200.00");

        callback(id, "RAIL-5", "SUCCEEDED");     // rail was faster than our bookkeeping
        assertThat(withdrawalStatus(m, id)).isEqualTo("COMPLETED");

        dispatcher.dispatchDue();                 // the queued submission sees it is resolved
        verify(payoutRail, never()).submit(any());
        assertThat(held(m, "ZAR")).isEqualTo("0.00");
        assertLedgerHealthy();
    }

    @Test
    void callbackForUnknownWithdrawalIs404() throws Exception {
        postJson("/api/v1/payout-callbacks", Map.of("withdrawalId", "nope", "status", "SUCCEEDED"))
                .andExpect(status().isNotFound());
    }
}
