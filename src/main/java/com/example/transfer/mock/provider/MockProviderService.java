package com.example.transfer.mock.provider;

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
import com.example.transfer.mock.provider.MockProviderDtos.PaymentRequest;
import com.example.transfer.mock.provider.MockProviderDtos.PaymentResponse;
import com.example.transfer.mock.provider.MockProviderDtos.SettleRequest;

@Service
public class MockProviderService {

    private static final Logger log = LoggerFactory.getLogger(MockProviderService.class);

    private final JdbcTemplate jdbc;
    private final MockProviderRepository repository;

    public MockProviderService(
            JdbcTemplate jdbc,
            MockProviderRepository repository
    ) {
        this.jdbc = jdbc;
        this.repository = repository;
    }

    @Transactional
    public PaymentResponse submit(PaymentRequest request) {
        validate(request);
        AdvisoryLocks.lock(jdbc, "provider:" + request.operationReference());

        String hash = fingerprint(request);

        List<UUID> inserted = jdbc.query("""
                INSERT INTO mock_provider_payments (
                    id, operation_reference, request_hash,
                    destination_account, destination_bank_code,
                    amount, currency, status, committed
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', TRUE)
                ON CONFLICT (operation_reference) DO NOTHING
                RETURNING id
                """,
                (rs, row) -> rs.getObject("id", UUID.class),
                UUID.randomUUID(),
                request.operationReference(),
                hash,
                request.destinationAccount(),
                request.destinationBankCode(),
                request.amount(),
                request.currency()
        );

        if (inserted.isEmpty()) {
            return jdbc.queryForObject("""
                    SELECT operation_reference, request_hash,
                           status, committed
                    FROM mock_provider_payments
                    WHERE operation_reference = ?
                    """,
                    (rs, row) -> {
                        if (!hash.equals(rs.getString("request_hash"))) {
                            throw new IllegalArgumentException(
                                    "Payment reference reused with different request"
                            );
                        }

                        PaymentResponse existing = new PaymentResponse(
                                rs.getString("operation_reference"),
                                rs.getString("status"),
                                rs.getBoolean("committed")
                        );
                        log.info(
                                "Returned existing provider payment {} status={} without accepting another payment",
                                existing.operationReference(),
                                existing.status()
                        );
                        return existing;
                    },
                    request.operationReference()
            );
        }

        log.info(
                "Accepted provider payment {} and left it pending settlement",
                request.operationReference()
        );
        return new PaymentResponse(
                request.operationReference(),
                "PENDING",
                true
        );
    }

    @Transactional
    public PaymentResponse settle(
            String reference,
            SettleRequest request
    ) {
        if (request == null
                || (!"SUCCESS".equals(request.status())
                && !"FAILED".equals(request.status()))) {
            throw new IllegalArgumentException(
                    "Settlement status must be SUCCESS or FAILED"
            );
        }

        // Only PENDING payments can transition to a final outcome.
        int updated = jdbc.update("""
                UPDATE mock_provider_payments
                SET status = ?, updated_at = NOW()
                WHERE operation_reference = ?
                  AND status = 'PENDING'
                """,
                request.status(),
                reference
        );

        PaymentResponse existing = repository.find(reference)
                .orElseThrow(()
                        -> new IllegalArgumentException("Payment not found")
                );

        if (updated == 0
                && !existing.status().equals(request.status())) {
            throw new IllegalArgumentException(
                    "Payment already has a conflicting final outcome"
            );
        }

        return existing;
    }

    private void validate(PaymentRequest request) {
        if (request == null
                || request.operationReference() == null
                || request.operationReference().isBlank()
                || request.operationReference().length() > 150
                || request.destinationAccount() == null
                || request.destinationAccount().isBlank()
                || request.destinationBankCode() == null
                || request.destinationBankCode().isBlank()
                || request.amount() == null
                || request.amount().signum() <= 0
                || request.amount().scale() > 2
                || request.amount().precision() - request.amount().scale() > 17
                || !"NGN".equals(request.currency())) {
            throw new IllegalArgumentException(
                    "Invalid provider payment request"
            );
        }
    }

    private String fingerprint(PaymentRequest request) {
        try {
            String input = request.destinationAccount().length()
                    + ":" + request.destinationAccount()
                    + "\n" + request.destinationBankCode().length()
                    + ":" + request.destinationBankCode()
                    + "\n" + request.amount()
                            .stripTrailingZeros().toPlainString()
                    + "\n" + request.currency();

            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
