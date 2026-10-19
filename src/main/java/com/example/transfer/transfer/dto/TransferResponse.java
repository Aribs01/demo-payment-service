package com.example.transfer.transfer.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.example.transfer.transfer.Transfer;
import com.example.transfer.transfer.TransferStatus;

public record TransferResponse(
        UUID id,
        String externalReference,
        String sourceAccount,
        String destinationAccount,
        String destinationBankCode,
        BigDecimal amount,
        String currency,
        String narration,
        TransferStatus status,
        boolean reconciliationRequired,
        Instant createdAt
        ) {

    public static TransferResponse from(Transfer transfer) {
        return new TransferResponse(
                transfer.getId(),
                transfer.getExternalReference(),
                transfer.getSourceAccount(),
                transfer.getDestinationAccount(),
                transfer.getDestinationBankCode(),
                transfer.getAmount(),
                transfer.getCurrency(),
                transfer.getNarration(),
                transfer.getStatus(),
                transfer.isReconciliationRequired(),
                transfer.getCreatedAt()
        );
    }
}
