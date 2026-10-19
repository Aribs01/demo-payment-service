package com.example.transfer.transfer.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Request to create or replay a transfer. The partner is taken from the API key, not this body.")
public record CreateTransferRequest(
        @NotBlank
        @Size(max = 150)
        @Schema(example = "merchant-000001")
        String externalReference,
        @NotBlank
        @Size(max = 50)
        @Schema(example = "1001610327")
        String sourceAccount,
        @NotBlank
        @Size(max = 50)
        @Schema(example = "0123456789")
        String destinationAccount,
        @NotBlank
        @Size(max = 20)
        @Schema(example = "000004")
        String destinationBankCode,
        @NotNull
        @DecimalMin(value = "0.00", inclusive = false)
        @Digits(integer = 17, fraction = 2)
        @Schema(example = "25000.00")
        BigDecimal amount,
        @NotBlank
        @Pattern(regexp = "NGN")
        @Schema(example = "NGN")
        String currency,
        @Size(max = 500)
        @Schema(example = "Invoice payment")
        String narration
        ) {}
