package com.corebank.transaction;

import com.corebank.common.web.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Sorts every Account Service response into three outcomes. The one that matters is Unknown: after a
 * timeout the money may or may not have moved, so the transfer must stay PENDING and never be marked failed.
 */
@Component
public class AccountClient {

    public sealed interface Outcome {
    }

    public record Applied(UUID fromCustomerId, UUID toCustomerId) implements Outcome {
    }

    public record Rejected(String code, String message) implements Outcome {
    }

    public record Unknown(String reason) implements Outcome {
    }

    public record AccountInfo(String accountNumber, UUID customerId) {
    }

    private record PostingRequest(UUID transactionId, BankTransaction.Kind kind, String fromAccount, String toAccount,
                                  BigDecimal amount, UUID initiatorCustomerId) {
    }

    private record PostingResponse(UUID fromCustomerId, UUID toCustomerId) {
    }

    private static final Logger log = LoggerFactory.getLogger(AccountClient.class);
    private static final int BUSY = 409;

    private final RestClient client;
    private final ObjectMapper mapper;

    public AccountClient(RestClient.Builder builder, ObjectMapper mapper,
                         @Value("${corebank.account-service-url}") String baseUrl,
                         @Value("${corebank.account-call.connect-timeout-ms}") int connectTimeout,
                         @Value("${corebank.account-call.read-timeout-ms}") int readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        this.client = builder.baseUrl(baseUrl).requestFactory(factory).build();
        this.mapper = mapper;
    }

    public Outcome post(BankTransaction t) {
        PostingRequest request = new PostingRequest(t.getId(), t.getType(), t.getFromAccount(), t.getToAccount(),
                t.getAmount(), t.getCustomerId());
        try {
            PostingResponse response = client.post().uri("/internal/postings").body(request)
                    .retrieve().body(PostingResponse.class);
            return new Applied(response.fromCustomerId(), response.toCustomerId());
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().value() == BUSY) {
                return new Unknown("Account busy");
            }
            return rejected(e);
        } catch (RestClientException e) {
            log.warn("Posting {} outcome unknown: {}", t.getId(), e.getMessage());
            return new Unknown(e.getMessage());
        }
    }

    public AccountInfo account(String accountNumber) {
        return client.get().uri("/internal/accounts/{number}", accountNumber).retrieve().body(AccountInfo.class);
    }

    private Rejected rejected(HttpClientErrorException e) {
        try {
            ApiError error = mapper.readValue(e.getResponseBodyAsString(), ApiError.class);
            return new Rejected(error.code(), error.message());
        } catch (Exception parseFailure) {
            return new Rejected("POSTING_REJECTED", e.getStatusText());
        }
    }
}
