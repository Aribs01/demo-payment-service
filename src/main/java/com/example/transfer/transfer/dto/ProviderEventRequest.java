package com.example.transfer.transfer.dto;

public record ProviderEventRequest(
        String providerEventId,
        String operationReference,
        String reportedStatus
        ) {}
