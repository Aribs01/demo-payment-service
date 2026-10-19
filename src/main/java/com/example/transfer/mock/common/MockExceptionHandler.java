package com.example.transfer.mock.common;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.example.transfer.mock")
public class MockExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(MockExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(
            IllegalArgumentException exception
    ) {
        log.warn("Mock rejected the request: {}", exception.getMessage());
        return ResponseEntity.badRequest()
                .body(Map.of("error", exception.getMessage()));
    }
}
