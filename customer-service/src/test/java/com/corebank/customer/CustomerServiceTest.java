package com.corebank.customer;

import com.corebank.common.web.ApiException;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "corebank.outbox.enabled=false",
        "spring.kafka.listener.auto-startup=false"
})
@Testcontainers
class CustomerServiceTest {

    private static final String PASSWORD = "password123";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&currentSchema=customer");
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    CustomerService service;
    @Autowired
    JdbcTemplate jdbc;

    private static String email() {
        return UUID.randomUUID() + "@example.com";
    }

    @Test
    void registerWritesTheCustomerAndItsEventTogether() {
        String email = email();

        Customer customer = service.register("Asha", email, null, PASSWORD, "CUSTOMER");

        assertThat(customer.getKycStatus()).isEqualTo(Customer.KycStatus.PENDING);
        Integer events = jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_key = ?",
                Integer.class, customer.getId().toString());
        assertThat(events).isEqualTo(1);
    }

    @Test
    void duplicateEmailIsRejected() {
        String email = email();
        service.register("Asha", email, null, PASSWORD, "CUSTOMER");

        assertThatThrownBy(() -> service.register("Other", email, null, PASSWORD, "CUSTOMER"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("EMAIL_TAKEN"));
    }

    @Test
    void loginIssuesAnAccessTokenCarryingTheCustomerAndRole() throws Exception {
        String email = email();
        Customer customer = service.register("Asha", email, null, PASSWORD, "CUSTOMER");

        CustomerService.Tokens tokens = service.login(email, PASSWORD);

        var claims = SignedJWT.parse(tokens.accessToken()).getJWTClaimsSet();
        assertThat(claims.getSubject()).isEqualTo(customer.getId().toString());
        assertThat(claims.getStringClaim("role")).isEqualTo("CUSTOMER");
    }

    @Test
    void wrongPasswordIsRejected() {
        String email = email();
        service.register("Asha", email, null, PASSWORD, "CUSTOMER");

        assertThatThrownBy(() -> service.login(email, "wrong-password"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("BAD_CREDENTIALS"));
    }

    @Test
    void aRefreshTokenWorksOnlyOnce() {
        String email = email();
        service.register("Asha", email, null, PASSWORD, "CUSTOMER");
        String refreshToken = service.login(email, PASSWORD).refreshToken();

        service.refresh(refreshToken);

        assertThatThrownBy(() -> service.refresh(refreshToken))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("INVALID_REFRESH_TOKEN"));
    }
}
