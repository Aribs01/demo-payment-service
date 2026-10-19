package com.example.transfer.mock.provider;

import java.math.BigDecimal;

public final class MockProviderDtos {

    private MockProviderDtos() {
    }

    public record PaymentRequest(
            String operationReference,
            String destinationAccount,
            String destinationBankCode,
            BigDecimal amount,
            String currency
            ) {}

    public record PaymentResponse(
            String operationReference,
            String status,
            boolean committed
            ) {}

    public record SettleRequest(String status) {}

    public record CallbackCommand(
            String mode,
            String status
            ) {}

    public record StatsResponse(long acceptedPayments) {}
}
