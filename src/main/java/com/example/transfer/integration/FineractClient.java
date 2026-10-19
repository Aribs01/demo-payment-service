package com.example.transfer.integration;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.example.transfer.mock.fault.FaultFlags;

@Component
public class FineractClient {

    private static final Logger log = LoggerFactory.getLogger(FineractClient.class);

    private final RestClient client;
    private final FaultFlags faults;

    public FineractClient(
            FaultFlags faults,
            @Value("${integrations.fineract.base-url}") String baseUrl,
            @Value("${app.mocks.admin-key}") String adminKey
    ) {
        this.faults = faults;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(2));
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("X-Mock-Admin-Key", adminKey)
                .requestFactory(factory)
                .build();
    }

    public FineractOperation debit(
            String operationReference,
            String accountNumber,
            BigDecimal amount
    ) {
        log.info(
                "Calling Fineract debit reference={} account={} amount={}",
                operationReference,
                accountNumber,
                amount
        );
        FineractOperation result = client.post()
                .uri("/debits")
                .headers(headers -> delay(headers, faults.fineractDelayAfterCommit()))
                .body(new OperationRequest(operationReference, accountNumber, amount, null))
                .retrieve()
                .body(FineractOperation.class);
        log.info(
                "Fineract debit returned reference={} status={} committed={}",
                result.operationReference(),
                result.status(),
                result.committed()
        );
        return result;
    }

    public FineractOperation refund(
            String operationReference,
            String accountNumber,
            BigDecimal amount,
            String originalDebitReference
    ) {
        log.info(
                "Calling Fineract refund reference={} originalDebit={} account={} amount={}",
                operationReference,
                originalDebitReference,
                accountNumber,
                amount
        );
        FineractOperation result = client.post()
                .uri("/refunds")
                .headers(headers -> delay(headers, faults.refundDelayAfterCommit()))
                .body(new OperationRequest(
                        operationReference,
                        accountNumber,
                        amount,
                        originalDebitReference
                ))
                .retrieve()
                .body(FineractOperation.class);
        log.info(
                "Fineract refund returned reference={} status={} committed={}",
                result.operationReference(),
                result.status(),
                result.committed()
        );
        return result;
    }

    public Optional<FineractOperation> find(String operationReference) {
        try {
            FineractOperation result = client.get()
                    .uri("/operations/{reference}", operationReference)
                    .retrieve()
                    .body(FineractOperation.class);
            log.info(
                    "Fineract lookup reference={} status={} committed={}",
                    operationReference,
                    result.status(),
                    result.committed()
            );
            return Optional.ofNullable(result);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                log.info("Fineract has no operation {}", operationReference);
                return Optional.empty();
            }
            throw exception;
        }
    }

    private void delay(org.springframework.http.HttpHeaders headers, boolean enabled) {
        if (enabled) {
            headers.set("X-Mock-Delay-After-Commit", "true");
        }
    }

    public record FineractOperation(
            String operationReference,
            String operationType,
            String status,
            boolean committed
            ) {}

    private record OperationRequest(
            String operationReference,
            String accountNumber,
            BigDecimal amount,
            String originalDebitReference
            ) {}
}
