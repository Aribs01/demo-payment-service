package com.example.transfer.transfer.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateTransferRequest(
        @NotBlank
        @Size(max = 150)
        String externalReference,
        @NotBlank
        @Size(max = 50)
        String sourceAccount,
        @NotBlank
        @Size(max = 50)
        String destinationAccount,
        @NotBlank
        @Size(max = 20)
        String destinationBankCode,
        @NotNull
        @DecimalMin(value = "0.00", inclusive = false)
        @Digits(integer = 17, fraction = 2)
        BigDecimal amount,
        @NotBlank
        @Pattern(regexp = "NGN")
        String currency,
        @Size(max = 500)
        String narration
        ) {}
