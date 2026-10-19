package com.example.transfer.transfer.service;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.transfer.transfer.dto.ProviderEventRequest;
import com.example.transfer.transfer.service.TransferStateStore.OperationRow;

@Service
public class ProviderEventService {

    private static final Logger log = LoggerFactory.getLogger(ProviderEventService.class);

    private final JdbcTemplate jdbc;
    private final TransferStateStore transfers;

    public ProviderEventService(JdbcTemplate jdbc, TransferStateStore transfers) {
        this.jdbc = jdbc;
        this.transfers = transfers;
    }

    @Transactional
    public boolean accept(ProviderEventRequest request) {
        validate(request);

        var inserted = jdbc.queryForList("""
                INSERT INTO provider_events (
                    id, provider_event_id, operation_reference,
                    reported_status, payload
                )
                VALUES (
                    ?, ?, ?, ?,
                    jsonb_build_object(
                        'providerEventId', ?,
                        'operationReference', ?,
                        'reportedStatus', ?
                    )
                )
                ON CONFLICT (provider_event_id) DO NOTHING
                RETURNING id
                """,
                UUID.randomUUID(),
                request.providerEventId(),
                request.operationReference(),
                request.reportedStatus(),
                request.providerEventId(),
                request.operationReference(),
                request.reportedStatus()
        );

        if (inserted.isEmpty() && alreadyProcessed(request.providerEventId())) {
            log.info(
                    "Duplicate provider callback ignored eventId={} reference={}",
                    request.providerEventId(),
                    request.operationReference()
            );
            return false;
        }

        apply(request);
        return true;
    }

    @Transactional
    public void applyStored(String operationReference) {
        var eventIds = jdbc.query("""
                SELECT provider_event_id
                FROM provider_events
                WHERE operation_reference = ?
                  AND processed_at IS NULL
                ORDER BY created_at
                """,
                (rs, row) -> rs.getString("provider_event_id"),
                operationReference
        );
        for (String eventId : eventIds) {
            load(eventId).ifPresent(this::apply);
        }
    }

    public Optional<UUID> findPaymentTransferId(String operationReference) {
        return transfers.findByReference(operationReference)
                .filter(operation -> "PAYMENT".equals(operation.operationType()))
                .map(OperationRow::transferId);
    }

    private void apply(ProviderEventRequest request) {
        Optional<OperationRow> payment = transfers.findByReference(request.operationReference())
                .filter(operation -> "PAYMENT".equals(operation.operationType()));
        if (payment.isEmpty()) {
            log.info(
                    "Callback stored before the provider submission response was saved eventId={} reference={}",
                    request.providerEventId(),
                    request.operationReference()
            );
            return;
        }

        UUID transferId = payment.get().transferId();
        String status = transfers.lockStatus(transferId);
        int claimed = jdbc.update("""
                UPDATE provider_events
                SET processed_at = NOW()
                WHERE provider_event_id = ?
                  AND processed_at IS NULL
                """,
                request.providerEventId()
        );
        if (claimed == 0) {
            log.info(
                    "Provider callback eventId={} was already processed",
                    request.providerEventId()
            );
            return;
        }

        if ("PAYMENT_PENDING".equals(status) || "DEBIT_CONFIRMED".equals(status)) {
            applyOutcome(transferId, payment.get().operationReference(), request);
            return;
        }

        if (confirms(status, request.reportedStatus())) {
            log.info(
                    "Provider callback confirms the existing outcome transferId={} status={} reported={}",
                    transferId,
                    status,
                    request.reportedStatus()
            );
            return;
        }

        transfers.flagReconciliation(
                transferId,
                "Callback reported " + request.reportedStatus()
                        + " after the transfer reached " + status
                        + ". Both facts were preserved."
        );
    }

    private void applyOutcome(
            UUID transferId,
            String operationReference,
            ProviderEventRequest request
    ) {
        if ("SUCCESS".equals(request.reportedStatus())) {
            transfers.updateOperation(operationReference, "SUCCEEDED");
            transfers.transition(
                    transferId,
                    currentOrPending(transferId),
                    "COMPLETED",
                    "TRANSFER_COMPLETED",
                    "Provider reported SUCCESS for " + operationReference
            );
            log.info(
                    "Provider success callback completed transfer {} reference={}",
                    transferId,
                    operationReference
            );
            return;
        }

        transfers.updateOperation(operationReference, "FAILED");
        String from = transfers.status(transferId).orElse("PAYMENT_PENDING");
        if ("DEBIT_CONFIRMED".equals(from)) {
            transfers.transition(
                    transferId,
                    "DEBIT_CONFIRMED",
                    "PAYMENT_PENDING",
                    "PAYMENT_PENDING",
                    "Payment operation exists before failure callback"
            );
            from = "PAYMENT_PENDING";
        }
        transfers.transition(
                transferId,
                from,
                "REFUND_PENDING",
                "REFUND_PENDING",
                "Provider reported FAILED for " + operationReference
        );
        log.info(
                "Provider failure callback moved transfer {} to refund reference={}",
                transferId,
                operationReference
        );
    }

    private String currentOrPending(UUID transferId) {
        String status = transfers.status(transferId).orElse("PAYMENT_PENDING");
        if ("DEBIT_CONFIRMED".equals(status)) {
            transfers.transition(
                    transferId,
                    "DEBIT_CONFIRMED",
                    "PAYMENT_PENDING",
                    "PAYMENT_PENDING",
                    "Payment evidence arrived before the submission response was saved"
            );
            return "PAYMENT_PENDING";
        }
        return status;
    }

    private boolean confirms(String transferStatus, String reportedStatus) {
        return ("COMPLETED".equals(transferStatus) && "SUCCESS".equals(reportedStatus))
                || ("REFUNDED".equals(transferStatus) && "FAILED".equals(reportedStatus))
                || ("REFUND_PENDING".equals(transferStatus) && "FAILED".equals(reportedStatus));
    }

    private boolean alreadyProcessed(String eventId) {
        Boolean processed = jdbc.queryForObject("""
                SELECT processed_at IS NOT NULL
                FROM provider_events
                WHERE provider_event_id = ?
                """,
                Boolean.class,
                eventId
        );
        return Boolean.TRUE.equals(processed);
    }

    private Optional<ProviderEventRequest> load(String eventId) {
        var rows = jdbc.query("""
                SELECT provider_event_id, operation_reference, reported_status
                FROM provider_events
                WHERE provider_event_id = ?
                """,
                (rs, row) -> new ProviderEventRequest(
                        rs.getString("provider_event_id"),
                        rs.getString("operation_reference"),
                        rs.getString("reported_status")
                ),
                eventId
        );
        return rows.stream().findFirst();
    }

    private void validate(ProviderEventRequest request) {
        if (request == null
                || request.providerEventId() == null
                || request.providerEventId().isBlank()
                || request.providerEventId().length() > 150
                || request.operationReference() == null
                || request.operationReference().isBlank()
                || request.operationReference().length() > 150
                || (!"SUCCESS".equals(request.reportedStatus())
                && !"FAILED".equals(request.reportedStatus()))) {
            throw new InvalidProviderEventException(
                    "Provider event requires an event id, operation reference, and status SUCCESS or FAILED"
            );
        }
    }
}
