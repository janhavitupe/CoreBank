package com.corebank.transaction;

import com.corebank.common.web.CorrelationIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Finishes transfers left PENDING by a crash or timeout. Repeating the posting is safe because Account
 * Service is idempotent on the transaction ID, so this either learns the earlier result or applies it now.
 */
@Component
public class RecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(RecoveryJob.class);

    private final TransactionRepository transactions;
    private final AccountClient accounts;
    private final TransactionService service;
    private final Duration staleAfter;

    public RecoveryJob(TransactionRepository transactions, AccountClient accounts, TransactionService service,
                       @Value("${corebank.recovery.stale-seconds}") long staleSeconds) {
        this.transactions = transactions;
        this.accounts = accounts;
        this.service = service;
        this.staleAfter = Duration.ofSeconds(staleSeconds);
    }

    @Scheduled(fixedDelayString = "${corebank.recovery.interval-ms}")
    public void recover() {
        for (BankTransaction t : transactions.findStalePending(Instant.now().minus(staleAfter), PageRequest.of(0, 50))) {
            MDC.put(CorrelationIdFilter.MDC_KEY, t.getCorrelationId());
            try {
                log.info("Recovering PENDING transaction {}", t.getId());
                service.settle(t, accounts.post(t));
            } finally {
                MDC.remove(CorrelationIdFilter.MDC_KEY);
            }
        }
    }
}
