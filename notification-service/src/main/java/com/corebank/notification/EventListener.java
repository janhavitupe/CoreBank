package com.corebank.notification;

import com.corebank.common.events.EventEnvelope;
import com.corebank.common.events.Topics;
import com.corebank.common.web.CorrelationIdFilter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class EventListener {

    private static final Logger log = LoggerFactory.getLogger(EventListener.class);

    private final NotificationService service;
    private final ObjectMapper mapper;

    public EventListener(NotificationService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @KafkaListener(topics = {Topics.CUSTOMER, Topics.TRANSACTION, Topics.ACCOUNT})
    public void onEvent(String message) throws JsonProcessingException {
        EventEnvelope event = mapper.readValue(message, EventEnvelope.class);
        if (event.correlationId() != null) {
            MDC.put(CorrelationIdFilter.MDC_KEY, event.correlationId());
        }
        try {
            log.info("Handling {} event {}", event.type(), event.eventId());
            service.handle(event);
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }
}
