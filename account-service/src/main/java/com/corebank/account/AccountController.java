package com.corebank.account;

import com.corebank.account.PostingService.Posting;
import com.corebank.account.PostingService.PostingResult;
import com.corebank.common.web.ApiException;
import com.corebank.common.web.Caller;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
public class AccountController {

    public record OpenAccountRequest(@NotNull Account.Type type) {
    }

    public record BalanceResponse(String accountNumber, BigDecimal balance) {
    }

    public record AccountSummary(String accountNumber, UUID customerId, Account.Type type, Account.Status status) {
        static AccountSummary of(Account account) {
            return new AccountSummary(account.getAccountNumber(), account.getCustomerId(), account.getType(),
                    account.getStatus());
        }
    }

    private final AccountService accounts;
    private final PostingService postings;

    public AccountController(AccountService accounts, PostingService postings) {
        this.accounts = accounts;
        this.postings = postings;
    }

    @PostMapping("/api/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public Account open(@Valid @RequestBody OpenAccountRequest request,
                        @RequestHeader(Caller.CUSTOMER_HEADER) UUID caller) {
        return accounts.open(caller, request.type());
    }

    @GetMapping("/api/accounts")
    public List<Account> mine(@RequestHeader(Caller.CUSTOMER_HEADER) UUID caller) {
        return accounts.forCustomer(caller);
    }

    @GetMapping("/api/accounts/{number}/balance")
    public BalanceResponse balance(@PathVariable String number,
                                   @RequestHeader(Caller.CUSTOMER_HEADER) UUID caller,
                                   @RequestHeader(Caller.ROLE_HEADER) String role) {
        requireOwnerOrAdmin(number, caller, role);
        return new BalanceResponse(number, accounts.balance(number));
    }

    @PostMapping("/api/accounts/{number}/freeze")
    public Account freeze(@PathVariable String number, @RequestHeader(Caller.ROLE_HEADER) String role) {
        Caller.requireAdmin(role);
        return accounts.freeze(number);
    }

    @PostMapping("/api/accounts/{number}/unfreeze")
    public Account unfreeze(@PathVariable String number, @RequestHeader(Caller.ROLE_HEADER) String role) {
        Caller.requireAdmin(role);
        return accounts.unfreeze(number);
    }

    @PostMapping("/api/accounts/{number}/close")
    public Account close(@PathVariable String number,
                         @RequestHeader(Caller.CUSTOMER_HEADER) UUID caller,
                         @RequestHeader(Caller.ROLE_HEADER) String role) {
        requireOwnerOrAdmin(number, caller, role);
        return accounts.close(number);
    }

    @GetMapping("/api/admin/reconciliation")
    public AccountService.Reconciliation reconciliation(@RequestHeader(Caller.ROLE_HEADER) String role) {
        Caller.requireAdmin(role);
        return accounts.reconcile();
    }

    // The /internal paths have no gateway route, so they are reachable only inside the service network.

    @PostMapping("/internal/postings")
    public PostingResult post(@RequestBody Posting posting) {
        return postings.apply(posting);
    }

    @GetMapping("/internal/accounts/{number}")
    public AccountSummary summary(@PathVariable String number) {
        return AccountSummary.of(accounts.byNumber(number));
    }

    @GetMapping("/internal/customers/{customerId}/accounts")
    public List<Account> forCustomer(@PathVariable UUID customerId) {
        return accounts.forCustomer(customerId);
    }

    private void requireOwnerOrAdmin(String number, UUID caller, String role) {
        if (!Caller.ADMIN.equals(role) && !accounts.byNumber(number).getCustomerId().equals(caller)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_YOUR_ACCOUNT", "You can only view your own accounts");
        }
    }
}
