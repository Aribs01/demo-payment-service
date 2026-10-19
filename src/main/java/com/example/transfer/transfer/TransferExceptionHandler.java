package com.example.transfer.transfer;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.transfer.transfer.service.IdempotencyConflictException;
import com.example.transfer.transfer.service.InvalidProviderEventException;
import com.example.transfer.transfer.service.SourceAccountNotAuthorizedException;
import com.example.transfer.transfer.service.TransferNotFoundException;

@RestControllerAdvice
public class TransferExceptionHandler {

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Map<String, String>> conflict(
            IdempotencyConflictException exception
    ) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(SourceAccountNotAuthorizedException.class)
    public ResponseEntity<Map<String, String>> forbidden(
            SourceAccountNotAuthorizedException exception
    ) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(TransferNotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(
            TransferNotFoundException exception
    ) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(InvalidProviderEventException.class)
    public ResponseEntity<Map<String, String>> invalidProviderEvent(
            InvalidProviderEventException exception
    ) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class
    })
    public ResponseEntity<Map<String, String>> badRequest(
            Exception exception
    ) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "Invalid transfer request"));
    }
}
