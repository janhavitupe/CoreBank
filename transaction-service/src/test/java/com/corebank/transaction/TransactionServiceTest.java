package com.corebank.transaction;

import com.corebank.common.web.ApiException;
import com.corebank.transaction.AccountClient.Applied;
import com.corebank.transaction.AccountClient.Rejected;
import com.corebank.transaction.AccountClient.Unknown;
import com.corebank.transaction.BankTransaction.Kind;
import com.corebank.transaction.BankTransaction.Status;
import com.corebank.transaction.TransactionService.Request;
import com.corebank.transaction.TransactionService.Submission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "corebank.outbox.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "corebank.recovery.interval-ms=3600000",
        "corebank.recovery.stale-seconds=0"
})
@Testcontainers
class TransactionServiceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&currentSchema=transaction");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @MockitoBean
    AccountClient accounts;

    @Autowired
    TransactionService service;
    @Autowired
    TransactionRepository transactions;
    @Autowired
    RecoveryJob recovery;
    @Autowired
    JdbcTemplate jdbc;

    private final UUID customer = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final Request request = new Request(Kind.TRANSFER, "CB1", "CB2", new BigDecimal("25.00"), "rent");

    @BeforeEach
    void clean() {
        reset(accounts);
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM transactions");
        jdbc.update("DELETE FROM idempotency_keys");
    }

    private int outboxRows() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class);
    }

    @Test
    void successfulTransferCompletesAndWritesOneEvent() {
        when(accounts.post(any())).thenReturn(new Applied(customer, other));

        Submission result = service.submit(customer, "key-1", request);

        assertThat(result.transaction().getStatus()).isEqualTo(Status.COMPLETED);
        assertThat(result.replayed()).isFalse();
        assertThat(outboxRows()).isEqualTo(1);
    }

    @Test
    void retryWithSameKeyReturnsStoredResultWithoutMovingMoneyAgain() {
        when(accounts.post(any())).thenReturn(new Applied(customer, other));

        Submission first = service.submit(customer, "key-1", request);
        Submission retry = service.submit(customer, "key-1", request);

        assertThat(retry.transaction().getId()).isEqualTo(first.transaction().getId());
        assertThat(retry.replayed()).isTrue();
        verify(accounts, times(1)).post(any());
        assertThat(outboxRows()).isEqualTo(1);
    }

    @Test
    void concurrentRequestsWithOneKeyPostOnce() throws Exception {
        when(accounts.post(any())).thenReturn(new Applied(customer, other));

        try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
            List<Future<Submission>> futures = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                futures.add(pool.submit(() -> service.submit(customer, "key-1", request)));
            }
            for (Future<Submission> future : futures) {
                future.get();
            }
        }

        verify(accounts, times(1)).post(any());
        assertThat(transactions.count()).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentBodyIsRejected() {
        when(accounts.post(any())).thenReturn(new Applied(customer, other));
        service.submit(customer, "key-1", request);
        Request changed = new Request(Kind.TRANSFER, "CB1", "CB2", new BigDecimal("999"), "rent");

        assertThatThrownBy(() -> service.submit(customer, "key-1", changed))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void keysAreScopedPerCustomer() {
        when(accounts.post(any())).thenReturn(new Applied(customer, other));

        service.submit(customer, "key-1", request);
        service.submit(other, "key-1", request);

        verify(accounts, times(2)).post(any());
    }

    @Test
    void rejectedTransferFailsWithoutAnEvent() {
        when(accounts.post(any())).thenReturn(new Rejected("INSUFFICIENT_FUNDS", "No money"));

        Submission result = service.submit(customer, "key-1", request);

        assertThat(result.transaction().getStatus()).isEqualTo(Status.FAILED);
        assertThat(result.transaction().getFailureCode()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(outboxRows()).isZero();
    }

    @Test
    void timeoutLeavesTransferPendingAndRecoveryCompletesItOnce() {
        when(accounts.post(any())).thenReturn(new Unknown("read timeout"));
        Submission result = service.submit(customer, "key-1", request);
        assertThat(result.transaction().getStatus()).isEqualTo(Status.PENDING);
        assertThat(outboxRows()).isZero();

        // Account Service had committed, so the repeated posting reports the earlier success.
        when(accounts.post(any())).thenReturn(new Applied(customer, other));
        recovery.recover();
        recovery.recover();

        BankTransaction settled = transactions.findById(result.transaction().getId()).orElseThrow();
        assertThat(settled.getStatus()).isEqualTo(Status.COMPLETED);
        assertThat(outboxRows()).isEqualTo(1);
    }

    @Test
    void recoveryFailsATransferThatAccountServiceRejects() {
        when(accounts.post(any())).thenReturn(new Unknown("connection refused"));
        Submission result = service.submit(customer, "key-1", request);

        when(accounts.post(any())).thenReturn(new Rejected("INSUFFICIENT_FUNDS", "No money"));
        recovery.recover();

        assertThat(transactions.findById(result.transaction().getId()).orElseThrow().getStatus()).isEqualTo(Status.FAILED);
        assertThat(outboxRows()).isZero();
    }

    @Test
    void settledTransfersAreLeftAlone() {
        when(accounts.post(any())).thenReturn(new Applied(customer, other));
        service.submit(customer, "key-1", request);
        clearInvocations(accounts);

        recovery.recover();

        verify(accounts, never()).post(any());
    }
}
