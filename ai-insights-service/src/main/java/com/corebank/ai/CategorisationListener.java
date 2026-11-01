package com.corebank.ai;

import com.corebank.common.events.EventEnvelope;
import com.corebank.common.events.Topics;
import com.corebank.common.outbox.OutboxWriter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;

/**
 * Publishes a category instead of writing it, because Transaction Service owns the transactions table.
 * The processed_events check runs first so a redelivered event does not repeat a paid Gemini call.
 */
@Component
public class CategorisationListener {

    private static final String COMPLETED = "TransactionCompleted";
    private static final String DEPOSIT = "DEPOSIT";

    private final Categoriser categoriser;
    private final OutboxWriter outbox;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public CategorisationListener(Categoriser categoriser, OutboxWriter outbox, JdbcTemplate jdbc,
                                  ObjectMapper mapper, PlatformTransactionManager txManager) {
        this.categoriser = categoriser;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(txManager);
    }

    @KafkaListener(topics = Topics.TRANSACTION)
    public void onTransaction(String message) throws JsonProcessingException {
        EventEnvelope event = mapper.readValue(message, EventEnvelope.class);
        if (!COMPLETED.equals(event.type()) || alreadyProcessed(event)) {
            return;
        }
        JsonNode p = event.payload();
        String category = DEPOSIT.equals(p.path("type").asText()) ? "transfer"
                : categoriser.categorise(p.path("description").asText(null), p.path("amount").asText());
        String transactionId = p.get("transactionId").asText();

        tx.executeWithoutResult(status -> {
            int firstTime = jdbc.update("INSERT INTO processed_events (event_id) VALUES (?) ON CONFLICT DO NOTHING",
                    event.eventId());
            if (firstTime == 1) {
                outbox.write(Topics.CATEGORISATION, transactionId, "TransactionCategorised",
                        Map.of("transactionId", transactionId, "category", category), event.correlationId());
            }
        });
    }

    private boolean alreadyProcessed(EventEnvelope event) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?",
                Integer.class, event.eventId());
        return count != null && count > 0;
    }
}
