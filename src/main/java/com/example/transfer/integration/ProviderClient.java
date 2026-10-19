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
public class ProviderClient {

    private static final Logger log = LoggerFactory.getLogger(ProviderClient.class);

    private final RestClient client;
    private final FaultFlags faults;

    public ProviderClient(
            FaultFlags faults,
            @Value("${integrations.provider.base-url}") String baseUrl,
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

    public ProviderPayment submit(
            String operationReference,
            String destinationAccount,
            String destinationBankCode,
            BigDecimal amount,
            String currency
    ) {
        log.info(
                "Submitting provider payment reference={} destination={} bank={} amount={}",
                operationReference,
                destinationAccount,
                destinationBankCode,
                amount
        );
        ProviderPayment result = client.post()
                .uri("/payments")
                .headers(headers -> {
                    if (faults.providerDelayAfterCommit()) {
                        headers.set("X-Mock-Delay-After-Commit", "true");
                    }
                })
                .body(new PaymentRequest(
                        operationReference,
                        destinationAccount,
                        destinationBankCode,
                        amount,
                        currency
                ))
                .retrieve()
                .body(ProviderPayment.class);
        log.info(
                "Provider submit returned reference={} status={} committed={}",
                result.operationReference(),
                result.status(),
                result.committed()
        );
        return result;
    }

    public Optional<ProviderPayment> find(String operationReference) {
        try {
            ProviderPayment result = client.get()
                    .uri("/payments/{reference}", operationReference)
                    .retrieve()
                    .body(ProviderPayment.class);
            log.info(
                    "Provider lookup reference={} status={} committed={}",
                    operationReference,
                    result.status(),
                    result.committed()
            );
            return Optional.ofNullable(result);
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                log.info("Provider has no payment {}", operationReference);
                return Optional.empty();
            }
            throw exception;
        }
    }

    public record ProviderPayment(
            String operationReference,
            String status,
            boolean committed
            ) {}

    private record PaymentRequest(
            String operationReference,
            String destinationAccount,
            String destinationBankCode,
            BigDecimal amount,
            String currency
            ) {}
}
