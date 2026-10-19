package com.example.transfer.mock.fineract;

import java.math.BigDecimal;

public final class MockFineractDtos {

    private MockFineractDtos() {
    }

    public record OperationRequest(
            String operationReference,
            String accountNumber,
            BigDecimal amount,
            String originalDebitReference
            ) {}

    public record OperationResponse(
            String operationReference,
            String operationType,
            String status,
            boolean committed
            ) {}

    public record AccountResponse(
            String accountNumber,
            BigDecimal balance,
            BigDecimal heldAmount,
            BigDecimal availableBalance
            ) {}

    public record StatsResponse(
            long debitEffects,
            long refundEffects
            ) {}
}
