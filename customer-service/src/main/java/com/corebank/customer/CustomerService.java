package com.corebank.customer;

import com.corebank.common.events.Topics;
import com.corebank.common.outbox.OutboxWriter;
import com.corebank.common.web.ApiException;
import com.corebank.customer.Repositories.CredentialRepository;
import com.corebank.customer.Repositories.CustomerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class CustomerService {

    public record Tokens(String accessToken, String refreshToken, long expiresInSeconds, UUID customerId, String role) {
    }

    private final CustomerRepository customers;
    private final CredentialRepository credentials;
    private final TokenService tokens;
    private final OutboxWriter outbox;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    public CustomerService(CustomerRepository customers, CredentialRepository credentials, TokenService tokens,
                           OutboxWriter outbox, JdbcTemplate jdbc) {
        this.customers = customers;
        this.credentials = credentials;
        this.tokens = tokens;
        this.outbox = outbox;
        this.jdbc = jdbc;
    }

    @Transactional
    public Customer register(String name, String email, String phone, String password, String role) {
        if (customers.existsByEmail(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_TAKEN", "That email is already registered");
        }
        Customer customer = customers.save(new Customer(name, email, phone));
        credentials.save(new Credential(customer.getId(), encoder.encode(password), role));
        outbox.write(Topics.CUSTOMER, customer.getId().toString(), "CustomerCreated", Map.of(
                "customerId", customer.getId(), "name", name, "email", email));
        return customer;
    }

    @Transactional
    public Tokens login(String email, String password) {
        Customer customer = customers.findByEmail(email).orElseThrow(CustomerService::badCredentials);
        Credential credential = credentials.findById(customer.getId()).orElseThrow(CustomerService::badCredentials);
        if (!encoder.matches(password, credential.getPasswordHash())) {
            throw badCredentials();
        }
        return issue(credential);
    }

    @Transactional
    public Tokens refresh(String refreshToken) {
        UUID customerId = tokens.consumeRefreshToken(refreshToken).orElseThrow(() ->
                new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "Refresh token is invalid or expired"));
        return issue(credentials.findById(customerId).orElseThrow());
    }

    @Transactional(readOnly = true)
    public Customer get(UUID id) {
        return customers.findById(id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND", "Customer not found"));
    }

    @Transactional
    public Customer updateKyc(UUID id, Customer.KycStatus status, String note) {
        Customer customer = get(id);
        customer.setKycStatus(status);
        jdbc.update("INSERT INTO kyc_records (id, customer_id, status, note) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), id, status.name(), note);
        return customer;
    }

    private Tokens issue(Credential credential) {
        return new Tokens(tokens.issueAccessToken(credential.getCustomerId(), credential.getRole()),
                tokens.issueRefreshToken(credential.getCustomerId()), tokens.accessTtlSeconds(),
                credential.getCustomerId(), credential.getRole());
    }

    private static ApiException badCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "Email or password is incorrect");
    }
}
