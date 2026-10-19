package com.example.transfer.transfer.service;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;

import com.example.transfer.integration.FineractClient;
import com.example.transfer.integration.FineractClient.FineractOperation;
import com.example.transfer.integration.ProviderClient;
import com.example.transfer.integration.ProviderClient.ProviderPayment;
import com.example.transfer.mock.fault.FaultFlags;
import com.example.transfer.transfer.service.TransferStateStore.OperationRow;

@Service
public class TransferWorkflow {

    private static final Logger log = LoggerFactory.getLogger(TransferWorkflow.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final TransferStateStore transfers;
    private final ProviderEventService providerEvents;
    private final FineractClient fineract;
    private final ProviderClient provider;
    private final FaultFlags faults;

    public TransferWorkflow(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            TransferStateStore transfers,
            ProviderEventService providerEvents,
            FineractClient fineract,
            ProviderClient provider,
            FaultFlags faults
    ) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.transfers = transfers;
        this.providerEvents = providerEvents;
        this.fineract = fineract;
        this.provider = provider;
        this.faults = faults;
    }

    public void advance(UUID transferId) {
        for (int step = 0; step < 4; step++) {
            String status = transfers.status(transferId).orElse(null);
            if (status == null) {
                log.warn("Transfer {} no longer exists", transferId);
                return;
            }
            boolean more = switch (status) {
                case "ACCEPTED", "DEBIT_PENDING" -> debit(transferId);
                case "DEBIT_CONFIRMED", "PAYMENT_PENDING" -> pay(transferId);
                case "REFUND_PENDING" -> refund(transferId);
                default -> false;
            };
            if (!more) {
                return;
            }
        }
    }

    private boolean debit(UUID transferId) {
        PreparedOperation prepared = transactions.execute(status -> prepare(
                transferId,
                "DEBIT",
                "debit-" + transferId,
                "ACCEPTED",
                "DEBIT_PENDING",
                "DEBIT_STARTED",
                "Debit operation reserved"
        ));
        if (prepared == null || prepared.finished()) {
            return prepared != null && prepared.continueWorkflow();
        }

        FineractOperation result;
        boolean recoveredFromLookup = false;
        try {
            Optional<FineractOperation> existing = fineract.find(prepared.reference());
            if (existing.isPresent()) {
                recoveredFromLookup = true;
                result = existing.get();
                log.info(
                        "Debit recovered from lookup without a new debit transferId={} reference={} status={}",
                        transferId,
                        prepared.reference(),
                        result.status()
                );
            } else {
                result = fineract.debit(
                        prepared.reference(),
                        prepared.sourceAccount(),
                        prepared.amount()
                );
                if (faults.consumeCrashAfterDebit()) {
                    log.error(
                            "Simulated worker crash after Fineract committed debit {} and before the result was saved for transfer {}",
                            prepared.reference(),
                            transferId
                    );
                    throw new IllegalStateException("Simulated crash after debit");
                }
            }
        } catch (ResourceAccessException exception) {
            log.warn(
                    "Fineract debit timed out for transfer {} reference={}; confirming by lookup",
                    transferId,
                    prepared.reference()
            );
            Optional<FineractOperation> confirmed = fineract.find(prepared.reference());
            if (confirmed.isEmpty()) {
                transactions.executeWithoutResult(status -> transfers.touch(transferId));
                log.info(
                        "Debit was not committed at Fineract for transfer {}; it will be retried with the same reference",
                        transferId
                );
                return false;
            }
            recoveredFromLookup = true;
            result = confirmed.get();
            log.info(
                    "Debit recovered from lookup after timeout without a new debit transferId={} reference={} status={}",
                    transferId,
                    prepared.reference(),
                    result.status()
            );
        }

        final FineractOperation confirmed = result;
        final boolean fromLookup = recoveredFromLookup;
        return Boolean.TRUE.equals(transactions.execute(status -> saveDebit(prepared, confirmed, fromLookup)));
    }

    private boolean saveDebit(
            PreparedOperation prepared,
            FineractOperation result,
            boolean fromLookup
    ) {
        if (!result.committed() || !"SUCCESS".equals(result.status())) {
            transfers.updateOperation(prepared.reference(), "FAILED");
            transfers.transition(
                    prepared.transferId(),
                    "DEBIT_PENDING",
                    "FAILED",
                    "DEBIT_FAILED",
                    "Fineract rejected the debit for insufficient available balance"
            );
            log.info(
                    "Transfer {} failed because the debit was not committed",
                    prepared.transferId()
            );
            return false;
        }

        transfers.updateOperation(prepared.reference(), "SUCCEEDED");
        transfers.transition(
                prepared.transferId(),
                "DEBIT_PENDING",
                "DEBIT_CONFIRMED",
                "DEBIT_CONFIRMED",
                fromLookup
                        ? "Confirmed the existing Fineract debit " + prepared.reference()
                        : "Fineract committed debit " + prepared.reference()
        );
        return true;
    }

    private boolean pay(UUID transferId) {
        PreparedOperation prepared = transactions.execute(status -> prepare(
                transferId,
                "PAYMENT",
                "payment-" + transferId,
                "DEBIT_CONFIRMED",
                "PAYMENT_PENDING",
                "PAYMENT_STARTED",
                "Payment operation reserved"
        ));
        if (prepared == null || prepared.finished()) {
            return prepared != null && prepared.continueWorkflow();
        }

        ProviderPayment lookup = null;
        try {
            Optional<ProviderPayment> existing = provider.find(prepared.reference());
            if (existing.isPresent()) {
                lookup = existing.get();
                log.info(
                        "Provider payment {} already exists with status {}; no additional payment was submitted",
                        prepared.reference(),
                        lookup.status()
                );
            } else {
                lookup = provider.submit(
                        prepared.reference(),
                        prepared.destinationAccount(),
                        prepared.destinationBankCode(),
                        prepared.amount(),
                        prepared.currency()
                );
            }
        } catch (ResourceAccessException exception) {
            log.warn(
                    "Provider submission timed out for transfer {} reference={}",
                    transferId,
                    prepared.reference()
            );
            lookup = provider.find(prepared.reference()).orElse(null);
            if (lookup == null) {
                transactions.executeWithoutResult(status -> transfers.touch(transferId));
                log.info(
                        "Provider has not accepted payment {} yet; it stays unresolved and will be retried",
                        prepared.reference()
                );
                return false;
            }
        }

        providerEvents.applyStored(prepared.reference());
        ProviderPayment observed = lookup;
        return Boolean.TRUE.equals(transactions.execute(
                status -> resolvePayment(prepared, observed)
        ));
    }

    private boolean resolvePayment(PreparedOperation prepared, ProviderPayment observed) {
        String status = transfers.lockStatus(prepared.transferId());
        if ("REFUND_PENDING".equals(status)) {
            return true;
        }
        if (!"PAYMENT_PENDING".equals(status)) {
            log.info(
                    "Transfer {} already reached {} before the provider response was applied",
                    prepared.transferId(),
                    status
            );
            return false;
        }
        if (observed == null || "PENDING".equals(observed.status())) {
            transfers.touch(prepared.transferId());
            log.info(
                    "Payment {} remains unresolved until a callback or a final provider lookup transferId={}",
                    prepared.reference(),
                    prepared.transferId()
            );
            return false;
        }
        if ("SUCCESS".equals(observed.status())) {
            transfers.updateOperation(prepared.reference(), "SUCCEEDED");
            transfers.transition(
                    prepared.transferId(),
                    "PAYMENT_PENDING",
                    "COMPLETED",
                    "TRANSFER_COMPLETED",
                    "Provider lookup reported SUCCESS for " + prepared.reference()
            );
            return false;
        }
        transfers.updateOperation(prepared.reference(), "FAILED");
        transfers.transition(
                prepared.transferId(),
                "PAYMENT_PENDING",
                "REFUND_PENDING",
                "REFUND_PENDING",
                "Provider lookup reported FAILED for " + prepared.reference()
        );
        return true;
    }

    private boolean refund(UUID transferId) {
        PreparedOperation prepared = transactions.execute(status -> {
            TransferRow transfer = lockTransfer(transferId);
            if (transfer == null || !"REFUND_PENDING".equals(transfer.status())) {
                return PreparedOperation.finished(false);
            }
            OperationRow debit = transfers.findOperation(transferId, "DEBIT")
                    .orElseThrow(() -> new IllegalStateException(
                            "Refund requested without a debit for " + transferId
                    ));
            OperationRow refund = transfers.ensureOperation(
                    transferId,
                    "REFUND",
                    "refund-" + transferId
            );
            if ("SUCCEEDED".equals(refund.status())) {
                return PreparedOperation.finished(false);
            }
            log.info(
                    "Refund operation {} reserved for transfer {} against debit {}",
                    refund.operationReference(),
                    transferId,
                    debit.operationReference()
            );
            return new PreparedOperation(
                    transfer.id(),
                    refund.operationReference(),
                    debit.operationReference(),
                    transfer.sourceAccount(),
                    transfer.destinationAccount(),
                    transfer.destinationBankCode(),
                    transfer.amount(),
                    transfer.currency(),
                    false,
                    false
            );
        });
        if (prepared == null || prepared.finished()) {
            return false;
        }

        FineractOperation result;
        try {
            Optional<FineractOperation> existing = fineract.find(prepared.reference());
            if (existing.isPresent()) {
                result = existing.get();
                log.info(
                        "Refund recovered from lookup without a new refund transferId={} reference={}",
                        transferId,
                        prepared.reference()
                );
            } else {
                result = fineract.refund(
                        prepared.reference(),
                        prepared.sourceAccount(),
                        prepared.amount(),
                        prepared.originalDebitReference()
                );
            }
        } catch (ResourceAccessException exception) {
            log.warn(
                    "Fineract refund timed out for transfer {} reference={}; confirming by lookup",
                    transferId,
                    prepared.reference()
            );
            Optional<FineractOperation> confirmed = fineract.find(prepared.reference());
            if (confirmed.isEmpty()) {
                transactions.executeWithoutResult(status -> transfers.touch(transferId));
                log.info(
                        "Refund was not committed for transfer {}; it will be retried with the same reference",
                        transferId
                );
                return false;
            }
            result = confirmed.get();
            log.info(
                    "Refund recovered from lookup after timeout without a new refund transferId={} reference={}",
                    transferId,
                    prepared.reference()
            );
        }

        FineractOperation confirmed = result;
        transactions.executeWithoutResult(status -> {
            if (!confirmed.committed() || !"SUCCESS".equals(confirmed.status())) {
                transfers.touch(prepared.transferId());
                log.error(
                        "Fineract did not commit refund {} for transfer {}",
                        prepared.reference(),
                        prepared.transferId()
                );
                return;
            }
            transfers.updateOperation(prepared.reference(), "SUCCEEDED");
            transfers.transition(
                    prepared.transferId(),
                    "REFUND_PENDING",
                    "REFUNDED",
                    "REFUND_CONFIRMED",
                    "Fineract committed refund " + prepared.reference()
            );
        });
        return false;
    }

    private PreparedOperation prepare(
            UUID transferId,
            String operationType,
            String operationReference,
            String expectedStatus,
            String pendingStatus,
            String eventType,
            String details
    ) {
        TransferRow transfer = lockTransfer(transferId);
        if (transfer == null) {
            return PreparedOperation.finished(false);
        }
        if (pendingStatus.equals(transfer.status()) || expectedStatus.equals(transfer.status())) {
            if (expectedStatus.equals(transfer.status())) {
                transfers.transition(
                        transferId,
                        expectedStatus,
                        pendingStatus,
                        eventType,
                        details
                );
            }
            OperationRow operation = transfers.ensureOperation(
                    transferId,
                    operationType,
                    operationReference
            );
            if ("SUCCEEDED".equals(operation.status())) {
                return PreparedOperation.finished("DEBIT".equals(operationType));
            }
            if ("FAILED".equals(operation.status())) {
                return PreparedOperation.finished(false);
            }
            log.info(
                    "{} operation {} is pending for transfer {}",
                    operationType,
                    operation.operationReference(),
                    transferId
            );
            return new PreparedOperation(
                    transfer.id(),
                    operation.operationReference(),
                    null,
                    transfer.sourceAccount(),
                    transfer.destinationAccount(),
                    transfer.destinationBankCode(),
                    transfer.amount(),
                    transfer.currency(),
                    false,
                    false
            );
        }
        return PreparedOperation.finished("REFUND_PENDING".equals(transfer.status()));
    }

    private TransferRow lockTransfer(UUID transferId) {
        var rows = jdbc.query("""
                SELECT id, status, source_account, destination_account,
                       destination_bank_code, amount, currency
                FROM transfers
                WHERE id = ?
                FOR UPDATE
                """,
                (rs, row) -> new TransferRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("status"),
                        rs.getString("source_account"),
                        rs.getString("destination_account"),
                        rs.getString("destination_bank_code"),
                        rs.getBigDecimal("amount"),
                        rs.getString("currency")
                ),
                transferId
        );
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private record TransferRow(
            UUID id,
            String status,
            String sourceAccount,
            String destinationAccount,
            String destinationBankCode,
            BigDecimal amount,
            String currency
            ) {}

    private record PreparedOperation(
            UUID transferId,
            String reference,
            String originalDebitReference,
            String sourceAccount,
            String destinationAccount,
            String destinationBankCode,
            BigDecimal amount,
            String currency,
            boolean finished,
            boolean continueWorkflow
            ) {
        static PreparedOperation finished(boolean continueWorkflow) {
            return new PreparedOperation(
                    null, null, null, null, null, null, null, null, true, continueWorkflow
            );
        }
    }
}
