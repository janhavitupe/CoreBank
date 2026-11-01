package com.corebank.common.outbox;

import com.corebank.common.events.EventEnvelope;
import com.corebank.common.web.CorrelationIdFilter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Stores an event in the caller's database transaction, so it exists only if the business change commits.
 * Propagation.MANDATORY makes calling this outside a transaction fail instead of silently losing the guarantee.
 */
public class OutboxWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public OutboxWriter(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void write(String topic, String key, String type, Object payload) {
        write(topic, key, type, payload, MDC.get(CorrelationIdFilter.MDC_KEY));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void write(String topic, String key, String type, Object payload, String correlationId) {
        UUID id = UUID.randomUUID();
        EventEnvelope envelope = new EventEnvelope(id, type, Instant.now(), correlationId, mapper.valueToTree(payload));
        try {
            jdbc.update("INSERT INTO outbox_events (id, topic, event_key, payload) VALUES (?, ?, ?, ?)",
                    id, topic, key, mapper.writeValueAsString(envelope));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise event " + type, e);
        }
    }
}
