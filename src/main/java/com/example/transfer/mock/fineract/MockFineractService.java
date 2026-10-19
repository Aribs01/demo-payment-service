package com.example.transfer.mock.fineract;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.transfer.common.AdvisoryLocks;
import com.example.transfer.mock.fineract.MockFineractDtos.OperationRequest;
import com.example.transfer.mock.fineract.MockFineractDtos.OperationResponse;

@Service
public class MockFineractService {

    private static final Logger log = LoggerFactory.getLogger(MockFineractService.class);

    private final JdbcTemplate jdbc;

    public MockFineractService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public OperationResponse debit(OperationRequest request) {
        validate(request, false);
        AdvisoryLocks.lock(jdbc, "fineract:" + request.operationReference());
        return execute(request, "DEBIT", null);
    }

    @Transactional
    public OperationResponse refund(OperationRequest request) {
        validate(request, true);
        AdvisoryLocks.lock(jdbc, "fineract:" + request.operationReference());
        AdvisoryLocks.lock(jdbc, "fineract-debit:" + request.originalDebitReference());
        requireRefundableDebit(request);

        OperationResponse existingRefund = findRefundForDebit(request.originalDebitReference());
        if (existingRefund != null) {
            if (!existingRefund.operationReference().equals(request.operationReference())) {
                throw new IllegalArgumentException(
                        "Debit already has a refund with a different operation reference"
                );
            }
            OperationResponse stored = requireMatchingOperation(
                    request.operationReference(),
                    fingerprint("REFUND", request)
            );
            log.info(
                    "Returned existing Fineract refund {} without a new balance change",
                    request.operationReference()
            );
            return stored;
        }

        return execute(request, "REFUND", request.originalDebitReference());
    }

    private OperationResponse execute(
            OperationRequest request,
            String type,
            String originalDebitReference
    ) {
        String fingerprint = fingerprint(type, request);

        List<UUID> inserted = jdbc.query("""
                INSERT INTO mock_fineract_operations (
                    id, operation_reference, operation_type,
                    request_hash, account_number, amount,
                    status, committed, original_debit_reference
                )
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', FALSE, ?)
                ON CONFLICT (operation_reference) DO NOTHING
                RETURNING id
                """,
                (rs, row) -> rs.getObject("id", UUID.class),
                UUID.randomUUID(),
                request.operationReference(),
                type,
                fingerprint,
                request.accountNumber(),
                request.amount(),
                originalDebitReference
        );

        if (inserted.isEmpty()) {
            OperationResponse existing = requireMatchingOperation(
                    request.operationReference(),
                    fingerprint
            );
            log.info(
                    "Returned existing Fineract {} {} status={} committed={} without a new balance change",
                    type,
                    existing.operationReference(),
                    existing.status(),
                    existing.committed()
            );
            return existing;
        }

        int updated;
        if ("DEBIT".equals(type)) {
            updated = jdbc.update("""
                    UPDATE mock_fineract_accounts
                    SET balance = balance - ?
                    WHERE account_number = ?
                      AND currency = 'NGN'
                      AND balance - held_amount >= ?
                    """,
                    request.amount(),
                    request.accountNumber(),
                    request.amount()
            );
        } else {
            updated = jdbc.update("""
                    UPDATE mock_fineract_accounts
                    SET balance = balance + ?
                    WHERE account_number = ?
                      AND currency = 'NGN'
                    """,
                    request.amount(),
                    request.accountNumber()
            );
        }

        String status = updated == 1 ? "SUCCESS" : "FAILED";
        boolean committed = updated == 1;

        jdbc.update("""
                UPDATE mock_fineract_operations
                SET status = ?, committed = ?
                WHERE operation_reference = ?
                """,
                status,
                committed,
                request.operationReference()
        );

        if (committed) {
            log.info(
                    "Committed Fineract {} reference={} account={} amount={}",
                    type,
                    request.operationReference(),
                    request.accountNumber(),
                    request.amount()
            );
        } else {
            log.info(
                    "Rejected Fineract {} for insufficient available balance reference={} account={} amount={}",
                    type,
                    request.operationReference(),
                    request.accountNumber(),
                    request.amount()
            );
        }

        return new OperationResponse(
                request.operationReference(),
                type,
                status,
                committed
        );
    }

    private void requireRefundableDebit(OperationRequest request) {
        List<DebitRow> debits = jdbc.query("""
                SELECT operation_type, account_number, amount, status, committed
                FROM mock_fineract_operations
                WHERE operation_reference = ?
                """,
                (rs, row) -> new DebitRow(
                        rs.getString("operation_type"),
                        rs.getString("account_number"),
                        rs.getBigDecimal("amount"),
                        rs.getString("status"),
                        rs.getBoolean("committed")
                ),
                request.originalDebitReference()
        );

        if (debits.isEmpty()) {
            throw new IllegalArgumentException("Original debit not found");
        }

        DebitRow debit = debits.getFirst();
        if (!"DEBIT".equals(debit.type())
                || !debit.committed()
                || !"SUCCESS".equals(debit.status())
                || !debit.accountNumber().equals(request.accountNumber())
                || debit.amount().compareTo(request.amount()) != 0) {
            throw new IllegalArgumentException("Original debit is not refundable");
        }
    }

    private OperationResponse findRefundForDebit(String originalDebitReference) {
        List<OperationResponse> refunds = jdbc.query("""
                SELECT operation_reference, operation_type, status, committed
                FROM mock_fineract_operations
                WHERE original_debit_reference = ?
                  AND operation_type = 'REFUND'
                """,
                (rs, row) -> new OperationResponse(
                        rs.getString("operation_reference"),
                        rs.getString("operation_type"),
                        rs.getString("status"),
                        rs.getBoolean("committed")
                ),
                originalDebitReference
        );
        return refunds.isEmpty() ? null : refunds.getFirst();
    }

    private OperationResponse requireMatchingOperation(
            String reference,
            String fingerprint
    ) {
        List<OperationResponse> rows = jdbc.query("""
                SELECT operation_reference, operation_type,
                       request_hash, status, committed
                FROM mock_fineract_operations
                WHERE operation_reference = ?
                """,
                (rs, row) -> {
                    if (!fingerprint.equals(rs.getString("request_hash"))) {
                        throw new IllegalArgumentException(
                                "Operation reference reused with different request"
                        );
                    }
                    return new OperationResponse(
                            rs.getString("operation_reference"),
                            rs.getString("operation_type"),
                            rs.getString("status"),
                            rs.getBoolean("committed")
                    );
                },
                reference
        );

        if (rows.isEmpty()) {
            throw new IllegalStateException(
                    "Fineract operation " + reference + " was not visible after the idempotency lock"
            );
        }
        return rows.getFirst();
    }

    private void validate(OperationRequest request, boolean refund) {
        if (request == null
                || request.operationReference() == null
                || request.operationReference().isBlank()
                || request.operationReference().length() > 150
                || request.accountNumber() == null
                || request.accountNumber().isBlank()
                || request.amount() == null
                || request.amount().signum() <= 0
                || request.amount().scale() > 2
                || request.amount().precision() - request.amount().scale() > 17) {
            throw new IllegalArgumentException("Invalid Fineract operation request");
        }
        if (refund && (request.originalDebitReference() == null
                || request.originalDebitReference().isBlank()
                || request.originalDebitReference().length() > 150)) {
            throw new IllegalArgumentException("Refund requires the original debit reference");
        }
        if (!refund && request.originalDebitReference() != null
                && !request.originalDebitReference().isBlank()) {
            throw new IllegalArgumentException("Debit must not name an original debit");
        }
    }

    private String fingerprint(String type, OperationRequest request) {
        try {
            String original = request.originalDebitReference() == null
                    ? ""
                    : request.originalDebitReference();
            String input = type + "\n"
                    + request.accountNumber().length() + ":"
                    + request.accountNumber()
                    + "\n"
                    + request.amount().stripTrailingZeros().toPlainString()
                    + "\n"
                    + original.length() + ":" + original;

            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record DebitRow(
            String type,
            String accountNumber,
            BigDecimal amount,
            String status,
            boolean committed
            ) {}
}
