package com.corebank.ai;

import com.corebank.common.web.ApiException;
import com.corebank.common.web.Caller;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.tools.FunctionTool;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
public class AssistantController {

    public record Question(@NotBlank @Size(max = 500) String question) {
    }

    public record Answer(String answer) {
    }

    private static final Logger log = LoggerFactory.getLogger(AssistantController.class);
    private static final String INSTRUCTION = """
            You are the CoreBank account assistant. Answer questions about the customer's own accounts and
            spending, using only your tools for facts, and never guess a number. Amounts are in rupees.
            You are read-only: you cannot move money or change anything, and if asked to, say so.
            Today is %s.
            """;

    private final GeminiAgent gemini;
    private final RestClient accounts;
    private final RestClient transactions;
    private final ObjectMapper mapper;

    public AssistantController(GeminiAgent gemini, RestClient.Builder builder, ObjectMapper mapper,
                               @Value("${corebank.account-service-url}") String accountUrl,
                               @Value("${corebank.transaction-service-url}") String transactionUrl) {
        this.gemini = gemini;
        this.accounts = builder.clone().baseUrl(accountUrl).build();
        this.transactions = builder.clone().baseUrl(transactionUrl).build();
        this.mapper = mapper;
    }

    @PostMapping("/api/ai/assistant")
    public Answer ask(@Valid @RequestBody Question question, @RequestHeader(Caller.CUSTOMER_HEADER) UUID caller) {
        if (!gemini.isConfigured()) {
            throw unavailable();
        }
        CustomerTools tools = new CustomerTools(accounts, transactions, mapper, caller);
        try {
            return new Answer(gemini.run("account_assistant", INSTRUCTION.formatted(LocalDate.now()),
                    List.of(FunctionTool.create(tools, "getAccounts"), FunctionTool.create(tools, "getSpending")),
                    caller.toString(), question.question()));
        } catch (RuntimeException e) {
            log.warn("Assistant failed: {}", e.getMessage());
            throw unavailable();
        }
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ASSISTANT_UNAVAILABLE", "The assistant is unavailable");
    }
}
