package com.example.transfer.mock.provider;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.example.transfer.mock.provider.MockProviderDtos.CallbackCommand;
import com.example.transfer.mock.provider.MockProviderDtos.PaymentResponse;
import com.example.transfer.mock.provider.MockProviderDtos.SettleRequest;

@Service
@ConditionalOnProperty(
        prefix = "app.mocks",
        name = "enabled",
        havingValue = "true"
)
public class MockProviderCallbackSender {

    private static final Logger log = LoggerFactory.getLogger(MockProviderCallbackSender.class);

    private final MockProviderService service;
    private final MockProviderRepository repository;
    private final RestClient callbacks;

    public MockProviderCallbackSender(
            MockProviderService service,
            MockProviderRepository repository,
            @Value("${app.provider.callback-url}") String callbackUrl,
            @Value("${app.provider.callback-secret}") String callbackSecret
    ) {
        this.service = service;
        this.repository = repository;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofSeconds(20));
        this.callbacks = RestClient.builder()
                .baseUrl(callbackUrl)
                .defaultHeader("X-Provider-Secret", callbackSecret)
                .requestFactory(factory)
                .build();
    }

    public void send(String operationReference, CallbackCommand command) {
        PaymentResponse payment = repository.find(operationReference)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found"));

        String mode = command == null || command.mode() == null
                ? "ONCE"
                : command.mode().trim().toUpperCase(Locale.ROOT);
        String status = command == null || command.status() == null
                ? "SUCCESS"
                : command.status().trim().toUpperCase(Locale.ROOT);
        if (!"SUCCESS".equals(status) && !"FAILED".equals(status)) {
            throw new IllegalArgumentException("Callback status must be SUCCESS or FAILED");
        }

        switch (mode) {
            case "ONCE" -> sendSettled(payment.operationReference(), status, UUID.randomUUID().toString());
            case "DUPLICATE" -> {
                String eventId = UUID.randomUUID().toString();
                sendSettled(payment.operationReference(), status, eventId);
                post(payment.operationReference(), status, eventId);
            }
            case "DELAYED" -> {
                log.info(
                        "Delaying provider callback for payment {} by 3 seconds",
                        payment.operationReference()
                );
                sleep();
                sendSettled(payment.operationReference(), status, UUID.randomUUID().toString());
            }
            case "CONFLICTING" -> {
                sendSettled(payment.operationReference(), status, UUID.randomUUID().toString());
                String opposite = "SUCCESS".equals(status) ? "FAILED" : "SUCCESS";
                post(payment.operationReference(), opposite, UUID.randomUUID().toString());
            }
            default -> throw new IllegalArgumentException(
                    "Callback mode must be ONCE, DUPLICATE, DELAYED, or CONFLICTING"
            );
        }
    }

    private void sendSettled(String reference, String status, String eventId) {
        service.settle(reference, new SettleRequest(status));
        post(reference, status, eventId);
    }

    private void post(String reference, String status, String eventId) {
        log.info(
                "Sending provider callback eventId={} reference={} status={}",
                eventId,
                reference,
                status
        );
        callbacks.post()
                .body(new CallbackPayload(eventId, reference, status))
                .retrieve()
                .toBodilessEntity();
        log.info(
                "Provider callback eventId={} reference={} was accepted by the transfer service",
                eventId,
                reference
        );
    }

    private void sleep() {
        try {
            Thread.sleep(3000);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Delayed callback interrupted", exception);
        }
    }

    private record CallbackPayload(
            String providerEventId,
            String operationReference,
            String reportedStatus
            ) {}
}
