package com.example.transfer.transfer.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Callback from the payment provider")
public record ProviderEventRequest(
        @Schema(example = "evt-000001")
        String providerEventId,
        @Schema(example = "payment-3fa85f64-5717-4562-b3fc-2c963f66afa6")
        String operationReference,
        @Schema(example = "SUCCESS", allowableValues = {"SUCCESS", "FAILED"})
        String reportedStatus
        ) {}
