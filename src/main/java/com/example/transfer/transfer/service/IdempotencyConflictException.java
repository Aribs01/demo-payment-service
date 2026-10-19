package com.example.transfer.transfer.service;

public class IdempotencyConflictException
        extends RuntimeException {

    public IdempotencyConflictException() {
        super("External reference already exists with a different payload");
    }
}
