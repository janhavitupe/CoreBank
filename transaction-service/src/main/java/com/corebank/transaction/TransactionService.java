package com.corebank.transaction;

import com.corebank.common.events.Topics;
import com.corebank.common.outbox.OutboxWriter;
import com.corebank.common.web.ApiException;
import com.corebank.common.web.CorrelationIdFilter;
import com.corebank.transaction.AccountClient.Applied;
import com.corebank.transaction.AccountClient.Outcome;
import com.corebank.transaction.AccountClient.Rejected;
import com.corebank.transaction.BankTransaction.Kind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * Runs a money movement in three separately committed steps, so a crash at any point is recoverable:
 * 1. Commit a PENDING transaction with its idempotency key, before any money moves.
 * 2. Ask Account Service to post it, which is idempotent on the transaction ID.
 * 3. Commit the outcome and its outbox event together.
 * A crash after step 2 leaves a PENDING row, which RecoveryJob completes by repeating step 2 safely.
 */
@Service
public class TransactionService {

    public record Request(Kind kind, String fromAccount, String toAccount, BigDecimal amount, String description) {
    }

    public record Submission(BankTransaction transaction, boolean replayed) {
    }

    private record Reservation(UUID transactionId, boolean isNew) {
    }

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    private final TransactionRepository transactions;
    private final AccountClient accounts;
    private final OutboxWriter outbox;
    private final JdbcTemplate jdbc;
    private final ChaosSwitch chaos;
    private final TransactionTemplate tx;

    public TransactionService(TransactionRepository transactions, AccountClient accounts, OutboxWriter outbox,
                              JdbcTemplate jdbc, ChaosSwitch chaos, PlatformTransactionManager txManager) {
        this.transactions = transactions;
        this.accounts = accounts;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.chaos = chaos;
        this.tx = new TransactionTemplate(txManager);
    }

    public Submission submit(UUID customerId, String idempotencyKey, Request request) {
        Reservation reservation = tx.execute(status -> reserve(customerId, idempotencyKey, request));
        if (!reservation.isNew()) {
            return new Submission(transactions.findById(reservation.transactionId()).orElseThrow(), true);
        }
        BankTransaction transaction = transactions.findById(reservation.transactionId()).orElseThrow();
        settle(transaction, accounts.post(transaction));
        return new Submission(transactions.findById(transaction.getId()).orElseThrow(), false);
    }

    /** ON CONFLICT DO NOTHING makes two concurrent requests with one key race safely: exactly one inserts. */
    private Reservation reserve(UUID customerId, String key, Request request) {
        String hash = hash(request);
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO idempotency_keys (customer_id, idempotency_key, request_hash, transaction_id)
                VALUES (?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, customerId, key, hash, id);
        if (inserted == 0) {
            Map<String, Object> existing = jdbc.queryForMap(
                    "SELECT request_hash, transaction_id FROM idempotency_keys WHERE customer_id = ? AND idempotency_key = ?",
                    customerId, key);
            if (!hash.equals(existing.get("request_hash"))) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED",
                        "This Idempotency-Key was already used with a different request");
            }
            return new Reservation((UUID) existing.get("transaction_id"), false);
        }
        transactions.saveAndFlush(new BankTransaction(id, request.kind(), request.fromAccount(), request.toAccount(),
                request.amount(), request.description(), customerId, key, MDC.get(CorrelationIdFilter.MDC_KEY)));
        return new Reservation(id, true);
    }

    /** Commits the outcome. Unknown changes nothing: the row stays PENDING for RecoveryJob. */
    public void settle(BankTransaction transaction, Outcome outcome) {
        chaos.crashIfArmed();
        log.info("Settling transaction {} outcome={}", transaction.getId(), outcome.getClass().getSimpleName());
        switch (outcome) {
            case Applied applied -> tx.executeWithoutResult(status -> complete(transaction, applied));
            case Rejected rejected -> tx.executeWithoutResult(status -> transactions.markFailed(
                    transaction.getId(), rejected.code(), rejected.message(), Instant.now()));
            case AccountClient.Unknown unknown -> {
            }
        }
    }

    private void complete(BankTransaction t, Applied applied) {
        int updated = transactions.markCompleted(t.getId(), applied.fromCustomerId(), applied.toCustomerId(), Instant.now());
        if (updated == 0) {
            return;
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("transactionId", t.getId());
        payload.put("type", t.getType());
        payload.put("fromAccount", t.getFromAccount());
        payload.put("toAccount", t.getToAccount());
        payload.put("fromCustomerId", applied.fromCustomerId());
        payload.put("toCustomerId", applied.toCustomerId());
        payload.put("amount", t.getAmount());
        payload.put("description", t.getDescription());
        outbox.write(Topics.TRANSACTION, t.getId().toString(), "TransactionCompleted", payload, t.getCorrelationId());
    }

    private static String hash(Request r) {
        String canonical = String.join("|", r.kind().name(), String.valueOf(r.fromAccount()), String.valueOf(r.toAccount()),
                r.amount().stripTrailingZeros().toPlainString(), String.valueOf(r.description()));
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
