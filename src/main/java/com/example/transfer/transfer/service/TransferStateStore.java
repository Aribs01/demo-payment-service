package com.example.transfer.transfer.service;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class TransferStateStore {

    private static final Logger log = LoggerFactory.getLogger(TransferStateStore.class);

    private final JdbcTemplate jdbc;

    public TransferStateStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> status(UUID transferId) {
        var rows = jdbc.query(
                "SELECT status FROM transfers WHERE id = ?",
                (rs, row) -> rs.getString("status"),
                transferId
        );
        return rows.stream().findFirst();
    }

    public String lockStatus(UUID transferId) {
        return jdbc.queryForObject(
                "SELECT status FROM transfers WHERE id = ? FOR UPDATE",
                String.class,
                transferId
        );
    }

    public boolean transition(
            UUID transferId,
            String from,
            String to,
            String eventType,
            String details
    ) {
        int updated = jdbc.update("""
                UPDATE transfers
                SET status = ?, updated_at = NOW()
                WHERE id = ? AND status = ?
                """,
                to,
                transferId,
                from
        );
        if (updated != 1) {
            return false;
        }
        audit(transferId, eventType, from, to, details);
        log.info(
                "Transfer {} moved from {} to {}: {}",
                transferId,
                from,
                to,
                details
        );
        return true;
    }

    public void touch(UUID transferId) {
        jdbc.update(
                "UPDATE transfers SET updated_at = NOW() WHERE id = ?",
                transferId
        );
    }

    public void flagReconciliation(UUID transferId, String details) {
        Boolean already = jdbc.queryForObject(
                "SELECT reconciliation_required FROM transfers WHERE id = ?",
                Boolean.class,
                transferId
        );
        if (Boolean.TRUE.equals(already)) {
            log.info(
                    "Transfer {} is already flagged for reconciliation",
                    transferId
            );
            return;
        }
        jdbc.update("""
                UPDATE transfers
                SET reconciliation_required = TRUE, updated_at = NOW()
                WHERE id = ?
                """,
                transferId
        );
        String status = status(transferId).orElse(null);
        audit(transferId, "CALLBACK_CONFLICT", status, status, details);
        log.warn(
                "Transfer {} kept status {} and was flagged for reconciliation: {}",
                transferId,
                status,
                details
        );
    }

    public void audit(
            UUID transferId,
            String eventType,
            String previousStatus,
            String newStatus,
            String details
    ) {
        jdbc.update("""
                INSERT INTO transfer_audit (
                    id, transfer_id, event_type,
                    previous_status, new_status, details
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                transferId,
                eventType,
                previousStatus,
                newStatus,
                details
        );
    }

    public OperationRow ensureOperation(
            UUID transferId,
            String operationType,
            String operationReference
    ) {
        jdbc.update("""
                INSERT INTO financial_operations (
                    id, transfer_id, operation_type,
                    operation_reference, status
                )
                VALUES (?, ?, ?, ?, 'PENDING')
                ON CONFLICT (transfer_id, operation_type) DO NOTHING
                """,
                UUID.randomUUID(),
                transferId,
                operationType,
                operationReference
        );
        return findOperation(transferId, operationType).orElseThrow(
                () -> new IllegalStateException(
                        "Financial operation " + operationType + " was not saved for " + transferId
                )
        );
    }

    public Optional<OperationRow> findOperation(UUID transferId, String operationType) {
        var rows = jdbc.query("""
                SELECT id, transfer_id, operation_type, operation_reference, status
                FROM financial_operations
                WHERE transfer_id = ? AND operation_type = ?
                """,
                (rs, row) -> mapOperation(rs),
                transferId,
                operationType
        );
        return rows.stream().findFirst();
    }

    public Optional<OperationRow> findByReference(String operationReference) {
        var rows = jdbc.query("""
                SELECT id, transfer_id, operation_type, operation_reference, status
                FROM financial_operations
                WHERE operation_reference = ?
                """,
                (rs, row) -> mapOperation(rs),
                operationReference
        );
        return rows.stream().findFirst();
    }

    public void updateOperation(String operationReference, String status) {
        jdbc.update("""
                UPDATE financial_operations
                SET status = ?, updated_at = NOW()
                WHERE operation_reference = ?
                """,
                status,
                operationReference
        );
    }

    private OperationRow mapOperation(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OperationRow(
                rs.getObject("id", UUID.class),
                rs.getObject("transfer_id", UUID.class),
                rs.getString("operation_type"),
                rs.getString("operation_reference"),
                rs.getString("status")
        );
    }

    public record OperationRow(
            UUID id,
            UUID transferId,
            String operationType,
            String operationReference,
            String status
            ) {}
}
