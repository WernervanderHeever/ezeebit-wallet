package com.ezeebit.wallet;

import com.ezeebit.wallet.common.CurrencyCode;
import com.ezeebit.wallet.fx.RateProvider;
import com.ezeebit.wallet.fx.RateService;
import com.ezeebit.wallet.fx.RateSnapshot;
import com.ezeebit.wallet.ledger.LedgerService;
import com.ezeebit.wallet.outbox.OutboxDispatcher;
import com.ezeebit.wallet.withdrawal.PayoutRail;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(IntegrationTestBase.TestClockConfig.class)
public abstract class IntegrationTestBase {

    @TestConfiguration
    static class TestClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected MutableClock clock;
    @Autowired protected LedgerService ledger;
    @Autowired protected OutboxDispatcher dispatcher;
    @Autowired protected RateService rateService;
    @Autowired protected JdbcTemplate jdbc;

    @MockBean protected RateProvider rateProvider;
    @MockBean protected PayoutRail payoutRail;

    /** Current mid price per "STABLE/FIAT" pair; tests move it to simulate the market. */
    protected final Map<String, BigDecimal> market = new ConcurrentHashMap<>();

    @BeforeEach
    void baseSetUp() {
        clock.reset();
        rateService.resetHistory();
        // Isolate tests that share the Spring context: nothing left over from a previous test gets dispatched.
        jdbc.update("UPDATE outbox_message SET status = 'DONE' WHERE status <> 'DONE'");
        market.clear();
        market.put("USDT/ZAR", new BigDecimal("18.25"));
        market.put("USDC/ZAR", new BigDecimal("18.24"));
        market.put("USDT/NGN", new BigDecimal("1550.00"));
        when(rateProvider.fetch(any(), any())).thenAnswer(inv -> {
            CurrencyCode stable = inv.getArgument(0);
            CurrencyCode fiat = inv.getArgument(1);
            return new RateSnapshot(stable, fiat, market.get(stable + "/" + fiat), clock.instant(), "test");
        });
    }

    // ------------------------------------------------------------------ helpers

    protected long createMerchant(String localCurrency) throws Exception {
        JsonNode body = readJson(postJson("/api/v1/merchants",
                Map.of("name", "Test Merchant", "country", "ZA", "localCurrency", localCurrency))
                .andExpect(status().isCreated()));
        return body.get("id").asLong();
    }

    protected void deposit(long merchantId, String currency, String amount) throws Exception {
        postJson("/api/v1/merchants/" + merchantId + "/deposits", Map.of(
                "source", "BANK_TRANSFER",
                "externalReference", "dep-" + System.nanoTime(),
                "currency", currency,
                "amount", amount))
                .andExpect(status().isCreated());
    }

    /** currency -> {available, held} */
    protected Map<String, String[]> balances(long merchantId) throws Exception {
        JsonNode body = readJson(mvc.perform(get("/api/v1/merchants/" + merchantId + "/balances"))
                .andExpect(status().isOk()));
        Map<String, String[]> result = new HashMap<>();
        body.forEach(b -> result.put(b.get("currency").asText(),
                new String[]{b.get("available").asText(), b.get("held").asText()}));
        return result;
    }

    protected String available(long merchantId, String currency) throws Exception {
        return balances(merchantId).get(currency)[0];
    }

    protected String held(long merchantId, String currency) throws Exception {
        return balances(merchantId).get(currency)[1];
    }

    protected void assertLedgerHealthy() {
        LedgerService.ReconciliationReport report = ledger.reconcile();
        assertThat(report.accountMismatches()).isEmpty();
        assertThat(report.unbalancedJournals()).isEmpty();
        assertThat(report.healthy()).as("ledger reconciliation: %s", report).isTrue();
    }

    protected ResultActions postJson(String path, Object body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    protected JsonNode readJson(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }
}
