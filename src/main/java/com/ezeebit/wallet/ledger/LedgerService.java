package com.ezeebit.wallet.ledger;

import com.ezeebit.wallet.common.Amounts;
import com.ezeebit.wallet.common.ApiException;
import com.ezeebit.wallet.common.CurrencyCode;
import com.ezeebit.wallet.common.InsufficientFundsException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The double-entry ledger. This is the only code that changes balances.
 *
 * <p>Invariants enforced here (and backed by database constraints):
 * <ul>
 *   <li>every account holds exactly one currency, and a posting's currency must match its account;</li>
 *   <li>the lines of a journal entry sum to zero <em>per currency</em>, so value is never created,
 *       destroyed, or moved between currencies except through the platform FX book;</li>
 *   <li>merchant accounts can never go negative (conditional update + CHECK constraint);</li>
 *   <li>postings are append-only and carry the running balance, so any balance can be explained;</li>
 *   <li>a business event (type + reference) can be posted at most once (unique key).</li>
 * </ul>
 *
 * <p>Concurrency: balances are changed with an atomic conditional {@code UPDATE}, which takes a row
 * lock. Accounts are always updated in ascending id order so concurrent journals cannot deadlock on
 * each other. The method must run inside the caller's transaction so the ledger change commits or
 * rolls back together with the business record (deposit, conversion, withdrawal) that caused it.
 */
@Service
public class LedgerService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public LedgerService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ writes

    @Transactional(propagation = Propagation.MANDATORY)
    public long post(JournalRequest request) {
        if (request.lines() == null || request.lines().size() < 2) {
            throw new IllegalArgumentException("A journal entry needs at least two lines");
        }

        // Resolve accounts and aggregate per account; TreeMap gives a deterministic lock order.
        Map<Long, BigDecimal> deltaByAccount = new TreeMap<>();
        Map<Long, CurrencyCode> currencyByAccount = new LinkedHashMap<>();
        Map<CurrencyCode, BigDecimal> netByCurrency = new EnumMap<>(CurrencyCode.class);

        for (JournalLine line : request.lines()) {
            CurrencyCode currency = line.account().currency();
            BigDecimal amount = line.amount();
            if (amount == null || amount.signum() == 0) {
                throw new IllegalArgumentException("Journal lines must have a non-zero amount");
            }
            if (amount.stripTrailingZeros().scale() > currency.scale()) {
                throw new IllegalArgumentException("Amount " + amount + " exceeds " + currency + " precision");
            }
            ResolvedAccount account = resolve(line.account());
            if (account.currency() != currency) {
                // Cannot happen via AccountRef, but this is the line that guarantees no silent mixing.
                throw new IllegalStateException("Currency mismatch on account " + account.id());
            }
            deltaByAccount.merge(account.id(), amount, BigDecimal::add);
            currencyByAccount.put(account.id(), currency);
            netByCurrency.merge(currency, amount, BigDecimal::add);
        }
        netByCurrency.forEach((currency, net) -> {
            if (net.signum() != 0) {
                throw new IllegalStateException("Unbalanced journal " + request.type() + " in " + currency + ": net " + net);
            }
        });

        Timestamp now = Timestamp.from(clock.instant());
        long journalId = insertJournal(request, now);

        for (Map.Entry<Long, BigDecimal> e : deltaByAccount.entrySet()) {
            long accountId = e.getKey();
            BigDecimal delta = e.getValue();
            CurrencyCode currency = currencyByAccount.get(accountId);
            if (delta.signum() == 0) {
                continue;
            }
            // Atomic check-and-apply. The row lock is held until commit.
            int updated = jdbc.update(
                    "UPDATE ledger_account SET balance = balance + ?, updated_at = ? "
                            + "WHERE id = ? AND (allow_negative = TRUE OR balance + ? >= 0)",
                    delta, now, accountId, delta);
            BigDecimal balanceAfter = jdbc.queryForObject(
                    "SELECT balance FROM ledger_account WHERE id = ?", BigDecimal.class, accountId);
            if (updated == 0) {
                throw new InsufficientFundsException(currency, balanceAfter, delta.negate());
            }
            jdbc.update(
                    "INSERT INTO ledger_posting (journal_entry_id, account_id, currency, amount, balance_after, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    journalId, accountId, currency.name(), delta, balanceAfter, now);
        }
        return journalId;
    }

    /** Opens an account if it does not exist yet. Idempotent. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void openAccount(AccountRef ref) {
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_account WHERE owner_key = ? AND currency = ? AND purpose = ?",
                Integer.class, ref.ownerKey(), ref.currency().name(), ref.purpose().name());
        if (existing != null && existing > 0) {
            return;
        }
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update(
                "INSERT INTO ledger_account (owner_key, merchant_id, currency, purpose, balance, allow_negative, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 0, ?, ?, ?)",
                ref.ownerKey(), ref.merchantId(), ref.currency().name(), ref.purpose().name(),
                ref.purpose().isPlatformAccount(), now, now);
    }

    /** Opens the merchant-side accounts needed to hold a currency. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void openMerchantCurrency(long merchantId, CurrencyCode currency) {
        openAccount(AccountRef.merchant(merchantId, currency, AccountPurpose.AVAILABLE));
        openAccount(AccountRef.merchant(merchantId, currency, AccountPurpose.WITHDRAWAL_HOLD));
    }

    // ------------------------------------------------------------------ reads

    public boolean hasAccount(AccountRef ref) {
        return findAccount(ref) != null;
    }

    public BigDecimal balanceOf(AccountRef ref) {
        ResolvedAccount account = resolve(ref);
        return jdbc.queryForObject("SELECT balance FROM ledger_account WHERE id = ?", BigDecimal.class, account.id());
    }

    /**
     * Balances per currency for a merchant. With {@code asOf}, balances are reconstructed from the
     * running balance on the postings, i.e. "what did this merchant hold at that moment".
     */
    public List<CurrencyBalance> merchantBalances(long merchantId, Instant asOf) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, currency, purpose, balance FROM ledger_account WHERE merchant_id = ? ORDER BY currency, purpose",
                merchantId);
        Map<CurrencyCode, BigDecimal[]> byCurrency = new TreeMap<>();
        for (Map<String, Object> row : rows) {
            CurrencyCode currency = CurrencyCode.valueOf((String) row.get("currency"));
            AccountPurpose purpose = AccountPurpose.valueOf((String) row.get("purpose"));
            long accountId = ((Number) row.get("id")).longValue();
            BigDecimal balance = asOf == null ? (BigDecimal) row.get("balance") : balanceAt(accountId, asOf);
            BigDecimal[] pair = byCurrency.computeIfAbsent(currency, c -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            if (purpose == AccountPurpose.AVAILABLE) {
                pair[0] = balance;
            } else if (purpose == AccountPurpose.WITHDRAWAL_HOLD) {
                pair[1] = balance;
            }
        }
        List<CurrencyBalance> result = new ArrayList<>();
        byCurrency.forEach((currency, pair) -> result.add(new CurrencyBalance(
                currency.name(),
                Amounts.format(pair[0], currency),
                Amounts.format(pair[1], currency),
                Amounts.format(pair[0].add(pair[1]), currency))));
        return result;
    }

    private BigDecimal balanceAt(long accountId, Instant asOf) {
        List<BigDecimal> found = jdbc.queryForList(
                "SELECT balance_after FROM ledger_posting WHERE account_id = ? AND created_at <= ? ORDER BY id DESC LIMIT 1",
                BigDecimal.class, accountId, Timestamp.from(asOf));
        return found.isEmpty() ? BigDecimal.ZERO : found.get(0);
    }

    /** Line-by-line history of one account with running balance. Keyset-paginated by posting id. */
    public Statement statement(AccountRef ref, long afterPostingId, int limit) {
        ResolvedAccount account = resolve(ref);
        CurrencyCode currency = account.currency();
        int pageSize = Math.max(1, Math.min(limit, 500));
        List<StatementLine> lines = jdbc.query(
                "SELECT p.id, p.amount, p.balance_after, p.created_at, j.id AS journal_id, j.entry_type, "
                        + "j.reference_type, j.reference_id, j.description "
                        + "FROM ledger_posting p JOIN journal_entry j ON j.id = p.journal_entry_id "
                        + "WHERE p.account_id = ? AND p.id > ? ORDER BY p.id LIMIT ?",
                (rs, i) -> new StatementLine(
                        rs.getLong("id"),
                        rs.getLong("journal_id"),
                        rs.getString("entry_type"),
                        rs.getString("reference_type"),
                        rs.getString("reference_id"),
                        rs.getString("description"),
                        Amounts.format(rs.getBigDecimal("amount"), currency),
                        Amounts.format(rs.getBigDecimal("balance_after"), currency),
                        rs.getTimestamp("created_at").toInstant()),
                account.id(), afterPostingId, pageSize);
        BigDecimal current = jdbc.queryForObject("SELECT balance FROM ledger_account WHERE id = ?", BigDecimal.class, account.id());
        Long nextCursor = lines.size() == pageSize ? lines.get(lines.size() - 1).postingId() : null;
        return new Statement(account.id(), currency.name(), ref.purpose().name(),
                Amounts.format(current, currency), lines, nextCursor);
    }

    /**
     * Proves the ledger is internally consistent:
     * cached balances equal the sum of their postings, every journal balances per currency,
     * and the whole system nets to zero per currency.
     */
    public ReconciliationReport reconcile() {
        List<String> accountMismatches = jdbc.query(
                "SELECT a.id, a.balance, COALESCE(SUM(p.amount), 0) AS posted "
                        + "FROM ledger_account a LEFT JOIN ledger_posting p ON p.account_id = a.id "
                        + "GROUP BY a.id, a.balance HAVING a.balance <> COALESCE(SUM(p.amount), 0)",
                (rs, i) -> "account " + rs.getLong("id") + ": cached " + rs.getBigDecimal("balance").toPlainString()
                        + " vs postings " + rs.getBigDecimal("posted").toPlainString());
        List<String> unbalancedJournals = jdbc.query(
                "SELECT journal_entry_id, currency, SUM(amount) AS net FROM ledger_posting "
                        + "GROUP BY journal_entry_id, currency HAVING SUM(amount) <> 0",
                (rs, i) -> "journal " + rs.getLong("journal_entry_id") + " " + rs.getString("currency")
                        + " nets to " + rs.getBigDecimal("net").toPlainString());
        Map<String, String> systemTotals = new TreeMap<>();
        jdbc.query("SELECT currency, SUM(balance) AS total FROM ledger_account GROUP BY currency",
                (RowCallbackHandler) rs -> systemTotals.put(rs.getString("currency"),
                        rs.getBigDecimal("total").stripTrailingZeros().toPlainString()));
        boolean totalsZero = systemTotals.values().stream().allMatch(v -> new BigDecimal(v).signum() == 0);
        boolean healthy = accountMismatches.isEmpty() && unbalancedJournals.isEmpty() && totalsZero;
        return new ReconciliationReport(healthy, accountMismatches, unbalancedJournals, systemTotals);
    }

    // ------------------------------------------------------------------ internals

    private long insertJournal(JournalRequest request, Timestamp now) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO journal_entry (entry_type, reference_type, reference_id, description, created_at) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, request.type().name());
            ps.setString(2, request.referenceType());
            ps.setString(3, request.referenceId());
            ps.setString(4, request.description());
            ps.setTimestamp(5, now);
            return ps;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("No id generated for journal entry");
        }
        return key.longValue();
    }

    private ResolvedAccount resolve(AccountRef ref) {
        ResolvedAccount account = findAccount(ref);
        if (account == null) {
            if (ref.purpose().isPlatformAccount()) {
                throw new IllegalStateException("Platform account missing: " + ref);
            }
            throw ApiException.unprocessable("ACCOUNT_NOT_OPEN",
                    "Merchant " + ref.merchantId() + " has no " + ref.currency() + " account");
        }
        return account;
    }

    private ResolvedAccount findAccount(AccountRef ref) {
        List<ResolvedAccount> found = jdbc.query(
                "SELECT id, currency FROM ledger_account WHERE owner_key = ? AND currency = ? AND purpose = ?",
                (rs, i) -> new ResolvedAccount(rs.getLong("id"), CurrencyCode.valueOf(rs.getString("currency"))),
                ref.ownerKey(), ref.currency().name(), ref.purpose().name());
        return found.isEmpty() ? null : found.get(0);
    }

    private record ResolvedAccount(long id, CurrencyCode currency) {
    }

    // ------------------------------------------------------------------ DTOs

    public record CurrencyBalance(String currency, String available, String held, String total) {
    }

    public record StatementLine(long postingId, long journalEntryId, String entryType, String referenceType,
                                String referenceId, String description, String amount, String balanceAfter,
                                Instant postedAt) {
    }

    public record Statement(long accountId, String currency, String purpose, String currentBalance,
                            List<StatementLine> lines, Long nextCursor) {
    }

    public record ReconciliationReport(boolean healthy, List<String> accountMismatches,
                                       List<String> unbalancedJournals, Map<String, String> systemTotalsByCurrency) {
    }
}
