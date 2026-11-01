package com.corebank.common.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Publishes unpublished outbox rows to Kafka. A crash between send and commit republishes the row,
 * so delivery is at least once and every consumer must deduplicate on eventId.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;

    public OutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka) {
        this.jdbc = jdbc;
        this.kafka = kafka;
    }

    private record Row(UUID id, String topic, String key, String payload) {
    }

    @Scheduled(fixedDelayString = "${corebank.outbox.poll-ms:500}")
    @Transactional
    public void publishPending() {
        List<Row> rows = jdbc.query("""
                SELECT id, topic, event_key, payload FROM outbox_events
                WHERE published_at IS NULL
                ORDER BY seq
                LIMIT 100
                FOR UPDATE SKIP LOCKED
                """, (rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getString("topic"),
                rs.getString("event_key"), rs.getString("payload")));

        for (Row row : rows) {
            try {
                kafka.send(row.topic(), row.key(), row.payload()).get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Kafka publish failed; will retry event {}: {}", row.id(), e.getMessage());
                return;
            }
            jdbc.update("UPDATE outbox_events SET published_at = now() WHERE id = ?", row.id());
        }
    }
}
