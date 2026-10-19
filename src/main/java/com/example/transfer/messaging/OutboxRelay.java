package com.example.transfer.messaging;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbitTemplate;

    public OutboxRelay(JdbcTemplate jdbc, RabbitTemplate rabbitTemplate) {
        this.jdbc = jdbc;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Scheduled(fixedDelay = 1000)
    public void publishPending() {
        List<OutboxEvent> events = jdbc.query("""
                SELECT id, aggregate_id
                FROM outbox_events
                WHERE published_at IS NULL
                ORDER BY created_at
                LIMIT 50
                """,
                (rs, row) -> new OutboxEvent(
                        rs.getObject("id", UUID.class),
                        rs.getObject("aggregate_id", UUID.class)
                )
        );

        for (OutboxEvent event : events) {
            publish(event);
        }
    }

    private void publish(OutboxEvent event) {
        try {
            rabbitTemplate.convertAndSend(
                    RabbitConfig.EXCHANGE,
                    RabbitConfig.ROUTING_KEY,
                    event.transferId().toString()
            );
            jdbc.update("""
                    UPDATE outbox_events
                    SET published_at = NOW()
                    WHERE id = ? AND published_at IS NULL
                    """,
                    event.id()
            );
            log.info(
                    "Published transfer {} to RabbitMQ after the database commit",
                    event.transferId()
            );
        } catch (RuntimeException exception) {
            jdbc.update("""
                    UPDATE outbox_events
                    SET attempts = attempts + 1
                    WHERE id = ?
                    """,
                    event.id()
            );
            log.warn(
                    "RabbitMQ is unavailable after transfer {} was accepted; the outbox row stays unpublished and will be sent when the broker returns",
                    event.transferId()
            );
        }
    }

    private record OutboxEvent(UUID id, UUID transferId) {}
}
