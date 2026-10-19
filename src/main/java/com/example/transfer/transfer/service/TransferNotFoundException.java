package com.example.transfer.transfer.service;

public class TransferNotFoundException extends RuntimeException {

    public TransferNotFoundException() {
        super("Transfer not found");
    }
}
