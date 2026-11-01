package com.corebank.transaction;

import com.corebank.common.events.EventEnvelope;
import com.corebank.common.events.Topics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Stores AI Insights results here, because this service owns the transactions table. Redelivery is harmless. */
@Component
public class CategoryListener {

    private final TransactionRepository transactions;
    private final ObjectMapper mapper;

    public CategoryListener(TransactionRepository transactions, ObjectMapper mapper) {
        this.transactions = transactions;
        this.mapper = mapper;
    }

    @KafkaListener(topics = Topics.CATEGORISATION)
    @Transactional
    public void onCategorised(String message) throws JsonProcessingException {
        EventEnvelope event = mapper.readValue(message, EventEnvelope.class);
        transactions.setCategory(UUID.fromString(event.payload().get("transactionId").asText()),
                event.payload().get("category").asText());
    }
}
