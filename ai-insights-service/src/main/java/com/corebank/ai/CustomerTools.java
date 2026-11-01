package com.corebank.ai;

import com.google.adk.tools.Annotations.Schema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The assistant's only tools. They are read-only, and customerId is fixed in the constructor from the
 * gateway's verified header, so nothing the model says can make them read another customer's data.
 */
public class CustomerTools {

    private final RestClient accounts;
    private final RestClient transactions;
    private final ObjectMapper mapper;
    private final UUID customerId;

    public CustomerTools(RestClient accounts, RestClient transactions, ObjectMapper mapper, UUID customerId) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.mapper = mapper;
        this.customerId = customerId;
    }

    @Schema(name = "get_accounts", description = "Lists the signed-in customer's accounts with type, status and balance.")
    public Map<String, Object> getAccounts() {
        JsonNode rows = accounts.get().uri("/internal/customers/{id}/accounts", customerId)
                .retrieve().body(JsonNode.class);
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (JsonNode row : rows) {
            result.add(Map.of("account", Masking.mask(row.get("accountNumber").asText()),
                    "type", row.get("type").asText(),
                    "status", row.get("status").asText(),
                    "balance", row.get("balance").asText()));
        }
        return Map.of("accounts", result);
    }

    @Schema(name = "get_spending", description = "Totals the signed-in customer's spending by category between two dates.")
    @SuppressWarnings("unchecked")
    public Map<String, Object> getSpending(
            @Schema(name = "from", description = "First day, as YYYY-MM-DD") String from,
            @Schema(name = "to", description = "Last day, as YYYY-MM-DD") String to) {
        JsonNode summary = transactions.get()
                .uri("/internal/customers/{id}/spending?from={from}&to={to}", customerId, from, to)
                .retrieve().body(JsonNode.class);
        return mapper.convertValue(summary, Map.class);
    }
}
