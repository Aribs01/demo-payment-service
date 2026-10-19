package com.example.transfer.transfer.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Acknowledgement of a provider callback")
public record ProviderEventResponse(
        @Schema(example = "accepted", description = "accepted for a new event, duplicate when the event id was already stored")
        String status
) {}
