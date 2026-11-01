package com.corebank.notification;

import com.corebank.common.events.EventEnvelope;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notifications;
    private final JdbcTemplate jdbc;
    private final JavaMailSender mail;
    private final String from;

    public NotificationService(NotificationRepository notifications, JdbcTemplate jdbc, JavaMailSender mail,
                               @Value("${corebank.mail-from}") String from) {
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.mail = mail;
        this.from = from;
    }

    /** The insert and the notifications commit together, so a redelivered event finds its ID taken and stops. */
    @Transactional
    public void handle(EventEnvelope event) {
        int firstTime = jdbc.update("INSERT INTO processed_events (event_id) VALUES (?) ON CONFLICT DO NOTHING",
                event.eventId());
        if (firstTime == 0) {
            log.info("Ignoring redelivered event {}", event.eventId());
            return;
        }
        JsonNode p = event.payload();
        switch (event.type()) {
            case "CustomerCreated" -> {
                UUID customerId = uuid(p, "customerId");
                jdbc.update("""
                        INSERT INTO customer_contacts (customer_id, name, email) VALUES (?, ?, ?)
                        ON CONFLICT (customer_id) DO UPDATE SET name = EXCLUDED.name, email = EXCLUDED.email
                        """, customerId, p.get("name").asText(), p.get("email").asText());
                send(customerId, event.eventId(), "Welcome to CoreBank, " + p.get("name").asText() + ".");
            }
            case "TransactionCompleted" -> {
                String amount = p.get("amount").decimalValue().stripTrailingZeros().toPlainString();
                if (p.hasNonNull("fromCustomerId")) {
                    send(uuid(p, "fromCustomerId"), event.eventId(),
                            "Debit of " + amount + " from account " + mask(p.get("fromAccount").asText()) + ".");
                }
                if (p.hasNonNull("toCustomerId")) {
                    send(uuid(p, "toCustomerId"), event.eventId(),
                            "Credit of " + amount + " to account " + mask(p.get("toAccount").asText()) + ".");
                }
            }
            case "LowBalanceDetected" -> send(uuid(p, "customerId"), event.eventId(),
                    "Account " + mask(p.get("accountNumber").asText()) + " is below your low-balance limit of "
                            + p.get("threshold").decimalValue().toPlainString() + ".");
            default -> log.warn("Ignoring unknown event type {}", event.type());
        }
    }

    public List<Notification> forCustomer(UUID customerId, org.springframework.data.domain.Pageable page) {
        return notifications.findByCustomerIdOrderByCreatedAtDesc(customerId, page);
    }

    private void send(UUID customerId, UUID eventId, String message) {
        notifications.save(new Notification(customerId, "IN_APP", message, "SENT", eventId));
        List<String> emails = jdbc.queryForList(
                "SELECT email FROM customer_contacts WHERE customer_id = ?", String.class, customerId);
        if (!emails.isEmpty()) {
            notifications.save(new Notification(customerId, "EMAIL", message, sendEmail(emails.get(0), message), eventId));
        }
    }

    private String sendEmail(String to, String message) {
        SimpleMailMessage email = new SimpleMailMessage();
        email.setFrom(from);
        email.setTo(to);
        email.setSubject("CoreBank alert");
        email.setText(message);
        try {
            mail.send(email);
            return "SENT";
        } catch (MailException e) {
            log.warn("Email failed: {}", e.getMessage());
            return "FAILED";
        }
    }

    private static UUID uuid(JsonNode payload, String field) {
        return UUID.fromString(payload.get(field).asText());
    }

    private static String mask(String accountNumber) {
        return "****" + accountNumber.substring(accountNumber.length() - 4);
    }
}
