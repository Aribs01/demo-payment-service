package com.example.transfer.transfer.service;

public class SourceAccountNotAuthorizedException
        extends RuntimeException {

    public SourceAccountNotAuthorizedException() {
        super("Client is not authorized to debit this source account");
    }
}
