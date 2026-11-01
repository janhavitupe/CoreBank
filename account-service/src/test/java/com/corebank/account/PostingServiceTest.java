package com.corebank.account;

import com.corebank.account.PostingService.Kind;
import com.corebank.account.PostingService.Posting;
import com.corebank.common.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "corebank.outbox.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "corebank.low-balance.check-ms=3600000"
})
@Testcontainers
class PostingServiceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&currentSchema=account");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @MockitoBean
    BalanceCache cache;

    @Autowired
    PostingService postings;
    @Autowired
    AccountService accounts;
    @Autowired
    AccountRepository repository;

    private Account funded(UUID customer, String amount) {
        Account account = accounts.open(customer, Account.Type.SAVINGS);
        if (new BigDecimal(amount).signum() > 0) {
            postings.apply(new Posting(UUID.randomUUID(), Kind.DEPOSIT, null, account.getAccountNumber(),
                    new BigDecimal(amount), customer));
        }
        return account;
    }

    private BigDecimal balance(Account account) {
        return repository.findByAccountNumber(account.getAccountNumber()).orElseThrow().getBalance();
    }

    private static Posting transfer(UUID id, Account from, Account to, String amount) {
        return new Posting(id, Kind.TRANSFER, from.getAccountNumber(), to.getAccountNumber(), new BigDecimal(amount),
                from.getCustomerId());
    }

    @Test
    void repeatingATransactionIdMovesMoneyOnce() {
        Account from = funded(UUID.randomUUID(), "100");
        Account to = funded(UUID.randomUUID(), "0");
        UUID id = UUID.randomUUID();

        assertThat(postings.apply(transfer(id, from, to, "30")).newlyApplied()).isTrue();
        var repeat = postings.apply(transfer(id, from, to, "30"));

        assertThat(repeat.newlyApplied()).isFalse();
        assertThat(repeat.fromCustomerId()).isEqualTo(from.getCustomerId());
        assertThat(balance(from)).isEqualByComparingTo("70");
        assertThat(balance(to)).isEqualByComparingTo("30");
    }

    @Test
    void concurrentRepeatsOfOneTransactionIdMoveMoneyOnce() throws Exception {
        Account from = funded(UUID.randomUUID(), "100");
        Account to = funded(UUID.randomUUID(), "0");
        UUID id = UUID.randomUUID();

        runConcurrently(12, i -> postings.apply(transfer(id, from, to, "30")));

        assertThat(balance(from)).isEqualByComparingTo("70");
        assertThat(balance(to)).isEqualByComparingTo("30");
    }

    @Test
    void concurrentTransfersNeverOverdrawAnAccount() throws Exception {
        Account from = funded(UUID.randomUUID(), "50");
        Account to = funded(UUID.randomUUID(), "0");
        AtomicInteger succeeded = new AtomicInteger();

        runConcurrently(12, i -> {
            try {
                postings.apply(transfer(UUID.randomUUID(), from, to, "10"));
                succeeded.incrementAndGet();
            } catch (ApiException expected) {
                // Insufficient funds or busy; both leave the balance untouched.
            }
        });

        assertThat(balance(from).signum()).isGreaterThanOrEqualTo(0);
        assertThat(balance(to)).isEqualByComparingTo(new BigDecimal(succeeded.get() * 10));
        assertThat(balance(from).add(balance(to))).isEqualByComparingTo("50");
    }

    @Test
    void oppositeTransfersDoNotDeadlockAndConserveMoney() throws Exception {
        Account a = funded(UUID.randomUUID(), "1000");
        Account b = funded(UUID.randomUUID(), "1000");

        runConcurrently(12, i -> {
            try {
                postings.apply(i % 2 == 0 ? transfer(UUID.randomUUID(), a, b, "1") : transfer(UUID.randomUUID(), b, a, "1"));
            } catch (ApiException expected) {
                // A busy response is allowed; a deadlock error is not.
            }
        });

        assertThat(balance(a).add(balance(b))).isEqualByComparingTo("2000");
    }

    @Test
    void insufficientFundsChangesNothing() {
        Account from = funded(UUID.randomUUID(), "10");
        Account to = funded(UUID.randomUUID(), "0");

        assertThatThrownBy(() -> postings.apply(transfer(UUID.randomUUID(), from, to, "11")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("INSUFFICIENT_FUNDS"));

        assertThat(balance(from)).isEqualByComparingTo("10");
        assertThat(balance(to)).isEqualByComparingTo("0");
    }

    @Test
    void frozenAccountCannotSendMoney() {
        Account from = funded(UUID.randomUUID(), "100");
        Account to = funded(UUID.randomUUID(), "0");
        accounts.freeze(from.getAccountNumber());

        assertThatThrownBy(() -> postings.apply(transfer(UUID.randomUUID(), from, to, "1")))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("ACCOUNT_FROZEN"));
    }

    @Test
    void customerCannotSpendFromSomeoneElsesAccount() {
        Account from = funded(UUID.randomUUID(), "100");
        Account to = funded(UUID.randomUUID(), "0");
        Posting stolen = new Posting(UUID.randomUUID(), Kind.TRANSFER, from.getAccountNumber(), to.getAccountNumber(),
                BigDecimal.ONE, to.getCustomerId());

        assertThatThrownBy(() -> postings.apply(stolen))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("NOT_YOUR_ACCOUNT"));
        assertThat(balance(from)).isEqualByComparingTo("100");
    }

    @Test
    void ledgerReconcilesWithBalances() {
        Account from = funded(UUID.randomUUID(), "100");
        Account to = funded(UUID.randomUUID(), "0");
        postings.apply(transfer(UUID.randomUUID(), from, to, "40"));

        AccountService.Reconciliation report = accounts.reconcile();

        assertThat(report.balanceMismatches()).isZero();
        assertThat(report.duplicateEntries()).isZero();
        assertThat(report.unbalancedTransfers()).isZero();
    }

    private interface Task {
        void run(int index);
    }

    private static void runConcurrently(int threads, Task task) throws InterruptedException, ExecutionException {
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int index = i;
                futures.add(pool.submit(() -> task.run(index)));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        }
    }
}
