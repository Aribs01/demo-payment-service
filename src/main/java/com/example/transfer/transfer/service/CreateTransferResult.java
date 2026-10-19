package com.example.transfer.transfer.service;

import com.example.transfer.transfer.dto.TransferResponse;

public record CreateTransferResult(
        TransferResponse response,
        boolean created
        ) {}
