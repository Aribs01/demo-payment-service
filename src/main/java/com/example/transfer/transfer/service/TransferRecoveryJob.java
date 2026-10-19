package com.example.transfer.transfer.service;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TransferRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(TransferRecoveryJob.class);

    private final JdbcTemplate jdbc;
    private final TransferWorkflow workflow;

    public TransferRecoveryJob(JdbcTemplate jdbc, TransferWorkflow workflow) {
        this.jdbc = jdbc;
        this.workflow = workflow;
    }

    @Scheduled(fixedDelay = 2000)
    public void recoverIncompleteTransfers() {
        List<UUID> transferIds = jdbc.query("""
                SELECT id
                FROM transfers
                WHERE (
                    status IN ('DEBIT_PENDING', 'DEBIT_CONFIRMED', 'REFUND_PENDING')
                    AND updated_at < NOW() - INTERVAL '2 seconds'
                ) OR (
                    status = 'PAYMENT_PENDING'
                    AND updated_at < NOW() - INTERVAL '15 seconds'
                ) OR (
                    status = 'ACCEPTED'
                    AND updated_at < NOW() - INTERVAL '2 seconds'
                    AND EXISTS (
                        SELECT 1
                        FROM outbox_events
                        WHERE aggregate_id = transfers.id
                          AND published_at IS NOT NULL
                    )
                )
                ORDER BY updated_at
                LIMIT 20
                """,
                (rs, row) -> rs.getObject("id", UUID.class)
        );

        for (UUID transferId : transferIds) {
            log.info("Recovery sweep is reprocessing transfer {}", transferId);
            try {
                workflow.advance(transferId);
            } catch (RuntimeException exception) {
                log.error(
                        "Recovery sweep failed for transfer {} and will try again",
                        transferId,
                        exception
                );
            }
        }
    }
}
