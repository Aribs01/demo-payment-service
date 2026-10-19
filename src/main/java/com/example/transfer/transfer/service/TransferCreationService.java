package com.example.transfer.transfer.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.transfer.common.AdvisoryLocks;
import com.example.transfer.transfer.Transfer;
import com.example.transfer.transfer.dto.CreateTransferRequest;
import com.example.transfer.transfer.repository.TransferRepository;

@Service
public class TransferCreationService {

    private static final Logger log = LoggerFactory.getLogger(TransferCreationService.class);

    private final JdbcTemplate jdbcTemplate;
    private final TransferRepository transferRepository;

    public TransferCreationService(
            JdbcTemplate jdbcTemplate,
            TransferRepository transferRepository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transferRepository = transferRepository;
    }

    @Transactional
    public StoredTransfer createOrGet(
            UUID clientId,
            CreateTransferRequest request
    ) {
        AdvisoryLocks.lock(
                jdbcTemplate,
                "transfer:" + clientId + ":" + request.externalReference()
        );
        String requestHash = TransferRequestHasher.hash(request);
        UUID transferId = UUID.randomUUID();

        try {
            var inserted = jdbcTemplate.queryForList("""
                    INSERT INTO transfers (
                        id, client_id, external_reference,
                        request_hash, source_account,
                        destination_account, destination_bank_code,
                        amount, currency, narration, status
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACCEPTED')
                    ON CONFLICT (client_id, external_reference)
                        DO NOTHING
                    RETURNING id
                    """,
                    transferId,
                    clientId,
                    request.externalReference(),
                    requestHash,
                    request.sourceAccount(),
                    request.destinationAccount(),
                    request.destinationBankCode(),
                    request.amount(),
                    request.currency(),
                    request.narration()
            );

            if (inserted.isEmpty()) {
                Transfer existing = transferRepository
                        .findByClientIdAndExternalReference(
                                clientId,
                                request.externalReference()
                        )
                        .orElseThrow(() -> new IllegalStateException(
                                "Conflicting transfer not found"
                        ));

                if (!existing.getRequestHash().equals(requestHash)) {
                    log.warn(
                            "Rejected reused external reference {} for client {} because the payload differs",
                            request.externalReference(),
                            clientId
                    );
                    throw new IdempotencyConflictException();
                }

                log.info(
                        "Returned existing transfer {} for client {} reference {} without creating another transfer",
                        existing.getId(),
                        clientId,
                        request.externalReference()
                );
                return new StoredTransfer(existing, false);
            }
        } catch (DataIntegrityViolationException exception) {
            if (isSourceAccountConstraint(exception)) {
                log.warn(
                        "Client {} is not authorized to debit source account {}",
                        clientId,
                        request.sourceAccount()
                );
                throw new SourceAccountNotAuthorizedException();
            }
            throw exception;
        }

        jdbcTemplate.update("""
                INSERT INTO transfer_audit (
                    id, transfer_id, event_type,
                    previous_status, new_status, details
                )
                VALUES (?, ?, 'TRANSFER_ACCEPTED',
                        NULL, 'ACCEPTED', 'Transfer accepted')
                """,
                UUID.randomUUID(),
                transferId
        );

        jdbcTemplate.update("""
                INSERT INTO outbox_events (
                    id, aggregate_id, event_type, payload
                )
                VALUES (?, ?, 'TRANSFER_ACCEPTED',
                        jsonb_build_object('transferId', CAST(? AS text)))
                """,
                UUID.randomUUID(),
                transferId,
                transferId.toString()
        );

        Transfer created = transferRepository.findById(transferId)
                .orElseThrow(() -> new IllegalStateException("New transfer not found"));
        log.info(
                "Accepted transfer {} for client {} reference {} and stored an outbox event",
                created.getId(),
                clientId,
                request.externalReference()
        );
        return new StoredTransfer(created, true);
    }

    private boolean isSourceAccountConstraint(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains("fk_transfers_source_account")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    public record StoredTransfer(Transfer transfer, boolean created) {}
}
