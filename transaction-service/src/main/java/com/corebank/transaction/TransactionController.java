package com.corebank.transaction;

import com.corebank.common.web.ApiException;
import com.corebank.common.web.Caller;
import com.corebank.transaction.BankTransaction.Kind;
import com.corebank.transaction.BankTransaction.Status;
import com.corebank.transaction.TransactionService.Request;
import com.corebank.transaction.TransactionService.Submission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
public class TransactionController {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    public record TransferRequest(@NotBlank String fromAccount, @NotBlank String toAccount,
                                  @NotNull @DecimalMin("0.01") @Digits(integer = 15, fraction = 4) BigDecimal amount,
                                  @Size(max = 255) String description) {
    }

    public record CashRequest(@NotBlank String accountNumber,
                              @NotNull @DecimalMin("0.01") @Digits(integer = 15, fraction = 4) BigDecimal amount,
                              @Size(max = 255) String description) {
    }

    public record CategoryTotal(String category, BigDecimal total, long count) {
    }

    public record SpendingSummary(LocalDate from, LocalDate to, BigDecimal total, List<CategoryTotal> categories) {
    }

    private final TransactionService service;
    private final TransactionRepository transactions;
    private final AccountClient accounts;
    private final ChaosSwitch chaos;

    public TransactionController(TransactionService service, TransactionRepository transactions,
                                 AccountClient accounts, ChaosSwitch chaos) {
        this.service = service;
        this.transactions = transactions;
        this.accounts = accounts;
        this.chaos = chaos;
    }

    @PostMapping("/api/transfers")
    public ResponseEntity<BankTransaction> transfer(@Valid @RequestBody TransferRequest body,
                                                    @RequestHeader(IDEMPOTENCY_HEADER) @Size(max = 100) String key,
                                                    @RequestHeader(Caller.CUSTOMER_HEADER) UUID caller) {
        return respond(service.submit(caller, key, new Request(Kind.TRANSFER, body.fromAccount(), body.toAccount(),
                body.amount(), body.description())));
    }

    @PostMapping("/api/deposits")
    public ResponseEntity<BankTransaction> deposit(@Valid @RequestBody CashRequest body,
                                                   @RequestHeader(IDEMPOTENCY_HEADER) @Size(max = 100) String key,
                                                   @RequestHeader(Caller.CUSTOMER_HEADER) UUID caller) {
        return respond(service.submit(caller, key, new Request(Kind.DEPOSIT, null, body.accountNumber(),
                body.amount(), body.description())));
    }

    @PostMapping("/api/withdrawals")
    public ResponseEntity<BankTransaction> withdraw(@Valid @RequestBody CashRequest body,
                                                    @RequestHeader(IDEMPOTENCY_HEADER) @Size(max = 100) String key,
                                                    @RequestHeader(Caller.CUSTOMER_HEADER) UUID caller) {
        return respond(service.submit(caller, key, new Request(Kind.WITHDRAWAL, body.accountNumber(), null,
                body.amount(), body.description())));
    }

    @GetMapping("/api/transactions/{id}")
    public BankTransaction get(@PathVariable UUID id,
                               @RequestHeader(Caller.CUSTOMER_HEADER) UUID caller,
                               @RequestHeader(Caller.ROLE_HEADER) String role) {
        BankTransaction t = transactions.findById(id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND", "Transaction not found"));
        if (!caller.equals(t.getCustomerId()) && !Caller.ADMIN.equals(role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_YOUR_TRANSACTION", "You can only view your own transactions");
        }
        return t;
    }

    @GetMapping("/api/statements/{accountNumber}")
    public List<BankTransaction> statement(@PathVariable String accountNumber,
                                           @RequestParam(defaultValue = "50") int limit,
                                           @RequestHeader(Caller.CUSTOMER_HEADER) UUID caller,
                                           @RequestHeader(Caller.ROLE_HEADER) String role) {
        if (!Caller.ADMIN.equals(role) && !accounts.account(accountNumber).customerId().equals(caller)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_YOUR_ACCOUNT", "You can only view your own accounts");
        }
        return transactions.statement(accountNumber, PageRequest.of(0, Math.min(limit, 200)));
    }

    @GetMapping("/api/transactions/summary")
    public SpendingSummary summary(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                   @RequestHeader(Caller.CUSTOMER_HEADER) UUID caller) {
        return spendingSummary(caller, from, to);
    }

    @PostMapping("/api/admin/chaos/crash-next-settlement")
    public Map<String, String> armCrash(@RequestHeader(Caller.ROLE_HEADER) String role) {
        Caller.requireAdmin(role);
        chaos.arm();
        return Map.of("status", "armed");
    }

    // The /internal paths have no gateway route. AI Insights calls them with an already-verified customer ID.

    @GetMapping("/internal/customers/{customerId}/spending")
    public SpendingSummary internalSpending(@PathVariable UUID customerId,
                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return spendingSummary(customerId, from, to);
    }

    private SpendingSummary spendingSummary(UUID customerId, LocalDate from, LocalDate to) {
        List<BankTransaction> rows = transactions.spending(customerId, from.atStartOfDay().toInstant(ZoneOffset.UTC),
                to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));
        Map<String, List<BankTransaction>> byCategory = rows.stream().collect(Collectors.groupingBy(
                t -> t.getCategory() == null ? "uncategorised" : t.getCategory(), TreeMap::new, Collectors.toList()));
        List<CategoryTotal> categories = byCategory.entrySet().stream()
                .map(e -> new CategoryTotal(e.getKey(), sum(e.getValue()), e.getValue().size()))
                .toList();
        return new SpendingSummary(from, to, sum(rows), categories);
    }

    private static BigDecimal sum(List<BankTransaction> rows) {
        return rows.stream().map(BankTransaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static ResponseEntity<BankTransaction> respond(Submission submission) {
        BankTransaction t = submission.transaction();
        HttpStatus status = t.getStatus() == Status.COMPLETED ? HttpStatus.CREATED
                : t.getStatus() == Status.PENDING ? HttpStatus.ACCEPTED
                : HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status)
                .header("Idempotent-Replayed", String.valueOf(submission.replayed()))
                .body(t);
    }
}
