package com.corebank.customer;

import com.corebank.common.web.ApiException;
import com.corebank.common.web.Caller;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
public class CustomerController {

    public record RegisterRequest(@NotBlank @Size(max = 120) String name, @NotBlank @Email String email,
                                  @Size(max = 20) String phone, @NotBlank @Size(min = 8, max = 72) String password) {
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record KycRequest(@NotNull Customer.KycStatus status, @Size(max = 255) String note) {
    }

    private final CustomerService service;
    private final TokenService tokens;

    public CustomerController(CustomerService service, TokenService tokens) {
        this.service = service;
        this.tokens = tokens;
    }

    @PostMapping("/api/customers")
    @ResponseStatus(HttpStatus.CREATED)
    public Customer register(@Valid @RequestBody RegisterRequest request) {
        return service.register(request.name(), request.email(), request.phone(), request.password(), "CUSTOMER");
    }

    @PostMapping("/api/auth/login")
    public CustomerService.Tokens login(@Valid @RequestBody LoginRequest request) {
        return service.login(request.email(), request.password());
    }

    @PostMapping("/api/auth/refresh")
    public CustomerService.Tokens refresh(@Valid @RequestBody RefreshRequest request) {
        return service.refresh(request.refreshToken());
    }

    @GetMapping("/api/customers/{id}")
    public Customer get(@PathVariable UUID id,
                        @RequestHeader(Caller.CUSTOMER_HEADER) UUID caller,
                        @RequestHeader(Caller.ROLE_HEADER) String role) {
        if (!id.equals(caller) && !Caller.ADMIN.equals(role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_YOUR_CUSTOMER", "You can only view your own profile");
        }
        return service.get(id);
    }

    @PatchMapping("/api/customers/{id}/kyc")
    public Customer updateKyc(@PathVariable UUID id, @Valid @RequestBody KycRequest request,
                              @RequestHeader(Caller.ROLE_HEADER) String role) {
        Caller.requireAdmin(role);
        return service.updateKyc(id, request.status(), request.note());
    }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return tokens.publicJwks();
    }
}
