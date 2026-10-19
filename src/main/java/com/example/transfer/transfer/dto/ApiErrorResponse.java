package com.example.transfer.transfer.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Error returned by the transfer API")
public record ApiErrorResponse(
        @Schema(example = "Invalid transfer request")
        String error
) {}
