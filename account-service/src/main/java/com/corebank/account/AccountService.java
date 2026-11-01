package com.corebank.account;

import com.corebank.common.events.Topics;
import com.corebank.common.outbox.OutboxWriter;
import com.corebank.common.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class AccountService {

    public record Reconciliation(long accountsChecked, long balanceMismatches, long duplicateEntries,
                                 long unbalancedTransfers, BigDecimal totalBalance) {
    }

    private final AccountRepository accounts;
    private final BalanceCache cache;
    private final OutboxWriter outbox;
    private final JdbcTemplate jdbc;
    private final BigDecimal lowBalanceThreshold;

    public AccountService(AccountRepository accounts, BalanceCache cache, OutboxWriter outbox, JdbcTemplate jdbc,
                          @Value("${corebank.low-balance.threshold}") BigDecimal lowBalanceThreshold) {
        this.accounts = accounts;
        this.cache = cache;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.lowBalanceThreshold = lowBalanceThreshold;
    }

    @Transactional
    public Account open(UUID customerId, Account.Type type) {
        String number = "CB" + ThreadLocalRandom.current().nextLong(1_000_000_000L, 10_000_000_000L);
        return accounts.save(new Account(customerId, number, type));
    }

    @Transactional(readOnly = true)
    public List<Account> forCustomer(UUID customerId) {
        return accounts.findByCustomerIdOrderByCreatedAt(customerId);
    }

    @Transactional(readOnly = true)
    public Account byNumber(String accountNumber) {
        return accounts.findByAccountNumber(accountNumber).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account not found"));
    }

    public BigDecimal balance(String accountNumber) {
        return cache.get(accountNumber, () -> byNumber(accountNumber).getBalance());
    }

    @Transactional
    public Account freeze(String accountNumber) {
        Account account = byNumber(accountNumber);
        account.freeze();
        return account;
    }

    @Transactional
    public Account unfreeze(String accountNumber) {
        Account account = byNumber(accountNumber);
        account.unfreeze();
        return account;
    }

    @Transactional
    public Account close(String accountNumber) {
        Account account = byNumber(accountNumber);
        account.close();
        return account;
    }

    @Scheduled(fixedDelayString = "${corebank.low-balance.check-ms}")
    @Transactional
    public void alertLowBalances() {
        LocalDate today = LocalDate.now();
        for (Account account : accounts.findNeedingLowBalanceAlert(lowBalanceThreshold, today)) {
            account.markLowBalanceAlerted(today);
            outbox.write(Topics.ACCOUNT, account.getId().toString(), "LowBalanceDetected", Map.of(
                    "customerId", account.getCustomerId(),
                    "accountNumber", account.getAccountNumber(),
                    "balance", account.getBalance(),
                    "threshold", lowBalanceThreshold));
        }
    }

    /** Proves the money invariants from the raw tables. Every count except the first must be zero. */
    @Transactional(readOnly = true)
    public Reconciliation reconcile() {
        long checked = count("SELECT count(*) FROM accounts");
        long mismatches = count("""
                SELECT count(*) FROM accounts a
                LEFT JOIN (
                    SELECT account_id,
                           sum(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END) AS net
                    FROM ledger_entries GROUP BY account_id
                ) l ON l.account_id = a.id
                WHERE a.balance <> coalesce(l.net, 0)
                """);
        long duplicates = count("""
                SELECT count(*) FROM (
                    SELECT 1 FROM ledger_entries GROUP BY transaction_id, entry_type HAVING count(*) > 1
                ) d
                """);
        long unbalanced = count("""
                SELECT count(*) FROM (
                    SELECT 1 FROM ledger_entries GROUP BY transaction_id
                    HAVING count(*) = 2
                       AND sum(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END) <> 0
                ) u
                """);
        BigDecimal total = jdbc.queryForObject("SELECT coalesce(sum(balance), 0) FROM accounts", BigDecimal.class);
        return new Reconciliation(checked, mismatches, duplicates, unbalanced, total);
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
