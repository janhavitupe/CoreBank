package com.corebank.account;

import com.corebank.account.LedgerEntry.Type;
import com.corebank.common.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Applies a debit and credit in one database transaction. Safe to call repeatedly with the same
 * transaction ID: ledger_entries is unique on (transaction_id, entry_type), so money moves at most once.
 */
@Service
public class PostingService {

    public enum Kind { TRANSFER, DEPOSIT, WITHDRAWAL }

    public record Posting(UUID transactionId, Kind kind, String fromAccount, String toAccount, BigDecimal amount,
                          UUID initiatorCustomerId) {
    }

    public record PostingResult(UUID transactionId, boolean newlyApplied, UUID fromCustomerId, UUID toCustomerId) {
    }

    private static final Logger log = LoggerFactory.getLogger(PostingService.class);
    private static final int MAX_ATTEMPTS = 5;

    private final AccountRepository accounts;
    private final LedgerRepository ledger;
    private final BalanceCache cache;
    private final TransactionTemplate tx;

    public PostingService(AccountRepository accounts, LedgerRepository ledger, BalanceCache cache,
                          PlatformTransactionManager txManager) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.cache = cache;
        this.tx = new TransactionTemplate(txManager);
    }

    public PostingResult apply(Posting posting) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                PostingResult result = tx.execute(status -> applyOnce(posting));
                evictAfterCommit(posting);
                log.info("Posting {} {} amount={} newlyApplied={}", posting.transactionId(), posting.kind(),
                        posting.amount(), result.newlyApplied());
                return result;
            } catch (ConcurrencyFailureException e) {
                // A busy account lost the version check or a lock race; nothing committed, so retry from fresh state.
                log.debug("Posting {} conflict on attempt {}", posting.transactionId(), attempt);
                sleepBeforeRetry(attempt);
            } catch (DataIntegrityViolationException e) {
                // The same transaction ID committed concurrently, so the unique constraint stopped a second debit.
                return tx.execute(status -> existingResult(posting.transactionId()));
            }
        }
        throw new ApiException(HttpStatus.CONFLICT, "ACCOUNT_BUSY", "Account is busy; retry the request");
    }

    private PostingResult applyOnce(Posting p) {
        if (!ledger.findByTransactionId(p.transactionId()).isEmpty()) {
            return existingResult(p.transactionId());
        }
        if (p.amount().signum() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT", "Amount must be positive");
        }
        return switch (p.kind()) {
            case TRANSFER -> transfer(p);
            case DEPOSIT -> {
                Account to = load(p.toAccount());
                requireOwner(to, p.initiatorCustomerId());
                credit(p, to);
                yield new PostingResult(p.transactionId(), true, null, to.getCustomerId());
            }
            case WITHDRAWAL -> {
                Account from = load(p.fromAccount());
                requireOwner(from, p.initiatorCustomerId());
                debit(p, from);
                yield new PostingResult(p.transactionId(), true, from.getCustomerId(), null);
            }
        };
    }

    private PostingResult transfer(Posting p) {
        if (p.fromAccount().equals(p.toAccount())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SAME_ACCOUNT", "Cannot transfer to the same account");
        }
        // Loading in a fixed order makes opposite transfers (A to B, B to A) update rows in the same order, avoiding deadlock.
        boolean fromFirst = p.fromAccount().compareTo(p.toAccount()) < 0;
        Account first = load(fromFirst ? p.fromAccount() : p.toAccount());
        Account second = load(fromFirst ? p.toAccount() : p.fromAccount());
        Account from = fromFirst ? first : second;
        Account to = fromFirst ? second : first;

        requireOwner(from, p.initiatorCustomerId());
        debit(p, from);
        credit(p, to);
        return new PostingResult(p.transactionId(), true, from.getCustomerId(), to.getCustomerId());
    }

    private void debit(Posting p, Account account) {
        account.debit(p.amount());
        ledger.save(new LedgerEntry(p.transactionId(), account.getId(), Type.DEBIT, p.amount(), account.getBalance()));
    }

    private void credit(Posting p, Account account) {
        account.credit(p.amount());
        ledger.save(new LedgerEntry(p.transactionId(), account.getId(), Type.CREDIT, p.amount(), account.getBalance()));
    }

    private PostingResult existingResult(UUID transactionId) {
        UUID from = null;
        UUID to = null;
        for (LedgerEntry entry : ledger.findByTransactionId(transactionId)) {
            UUID owner = accounts.findById(entry.getAccountId()).orElseThrow().getCustomerId();
            if (entry.getEntryType() == Type.DEBIT) {
                from = owner;
            } else {
                to = owner;
            }
        }
        return new PostingResult(transactionId, false, from, to);
    }

    private Account load(String accountNumber) {
        return accounts.findByAccountNumber(accountNumber).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account " + accountNumber + " not found"));
    }

    private static void requireOwner(Account account, UUID customerId) {
        if (!account.getCustomerId().equals(customerId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_YOUR_ACCOUNT", "You can only use your own accounts");
        }
    }

    private void evictAfterCommit(Posting p) {
        List<String> numbers = new ArrayList<>();
        if (p.fromAccount() != null) {
            numbers.add(p.fromAccount());
        }
        if (p.toAccount() != null) {
            numbers.add(p.toAccount());
        }
        numbers.forEach(cache::evict);
    }

    private static void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(5, 25L * attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.CONFLICT, "ACCOUNT_BUSY", "Account is busy; retry the request");
        }
    }
}
