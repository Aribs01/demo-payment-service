package com.example.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import com.example.transfer.messaging.RabbitConfig;
import com.example.transfer.mock.fault.FaultFlags;
import com.example.transfer.mock.provider.MockProviderDtos.CallbackCommand;
import com.example.transfer.transfer.TransferStatus;
import com.example.transfer.transfer.dto.CreateTransferRequest;
import com.example.transfer.transfer.dto.ProviderEventRequest;
import com.example.transfer.transfer.dto.TransferResponse;
import com.example.transfer.transfer.service.TransferWorkflow;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "server.port=18080",
                "integrations.fineract.base-url=http://127.0.0.1:18080/mock/fineract",
                "integrations.provider.base-url=http://127.0.0.1:18080/mock/provider",
                "app.provider.callback-url=http://127.0.0.1:18080/api/v2/provider/events",
                "spring.task.scheduling.pool.size=4"
        }
)
class TransferScenarioTests {

    private static final String SOURCE = "1001610327";
    private static final String DESTINATION = "0123456789";
    private static final String BANK = "000004";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FaultFlags faults;

    @Autowired
    private TransferWorkflow workflow;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitMQContainer rabbitContainer;

    private RestClient client;

    @BeforeEach
    void resetFixture() {
        faults.replace(false, false, false, false);
        jdbc.update("""
                UPDATE mock_fineract_accounts
                SET balance = 200000.00, held_amount = 60000.00
                WHERE account_number = ?
                """,
                SOURCE
        );
        client = RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();
    }

    @AfterEach
    void clearFaults() {
        faults.replace(false, false, false, false);
    }

    @Test
    void twentyIdenticalRequestsProduceOneTransferAndOneDebit() throws Exception {
        MoneyStats before = stats();
        CreateTransferRequest request = request("merchant-parallel-" + UUID.randomUUID(), "1000.00");

        ExecutorService pool = Executors.newFixedThreadPool(20);
        try {
            List<Future<ResponseEntity<TransferResponse>>> calls = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                calls.add(pool.submit(() -> postTransfer(request)));
            }
            List<ResponseEntity<TransferResponse>> responses = new ArrayList<>();
            for (Future<ResponseEntity<TransferResponse>> call : calls) {
                responses.add(call.get());
            }

            assertThat(responses).allSatisfy(response ->
                    assertThat(response.getStatusCode().value()).isIn(200, 202)
            );
            assertThat(responses.stream().filter(response -> response.getStatusCode().value() == 202)).hasSize(1);
            assertThat(responses.stream().map(response -> response.getBody().id()).distinct()).hasSize(1);
        } finally {
            pool.shutdownNow();
        }

        TransferResponse transfer = awaitStatus(request.externalReference(), TransferStatus.PAYMENT_PENDING);
        assertThat(stats().debits() - before.debits()).isEqualTo(1);
        assertThat(transfer.id()).isNotNull();
    }

    @Test
    void twoSimultaneousTransfersCannotSpendMoreThanAvailableBalance() throws Exception {
        MoneyStats before = stats();
        String first = "merchant-balance-a-" + UUID.randomUUID();
        String second = "merchant-balance-b-" + UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<TransferResponse>> firstCall = pool.submit(() ->
                    postTransfer(request(first, "80000.00"))
            );
            Future<ResponseEntity<TransferResponse>> secondCall = pool.submit(() ->
                    postTransfer(request(second, "80000.00"))
            );
            assertThat(firstCall.get().getStatusCode().value()).isEqualTo(202);
            assertThat(secondCall.get().getStatusCode().value()).isEqualTo(202);
        } finally {
            pool.shutdownNow();
        }

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            TransferStatus firstStatus = fetch(first).status();
            TransferStatus secondStatus = fetch(second).status();
            assertThat(List.of(firstStatus, secondStatus))
                    .containsExactlyInAnyOrder(TransferStatus.FAILED, TransferStatus.PAYMENT_PENDING);
        });
        assertThat(stats().debits() - before.debits()).isEqualTo(1);
    }

    @Test
    void debitCommittedBeforeTimeoutIsRecoveredWithoutAnotherDebit() {
        MoneyStats before = stats();
        faults.replace(true, false, false, false);
        CreateTransferRequest request = request("merchant-timeout-" + UUID.randomUUID(), "1000.00");

        ResponseEntity<TransferResponse> created = postTransfer(request);

        assertThat(created.getStatusCode().value()).isEqualTo(202);
        awaitStatus(request.externalReference(), TransferStatus.PAYMENT_PENDING);
        assertThat(stats().debits() - before.debits()).isEqualTo(1);
    }

    @Test
    void workerCrashAfterDebitIsRecoveredByReplacementWorker() {
        MoneyStats before = stats();
        faults.replace(false, false, false, true);
        CreateTransferRequest request = request("merchant-crash-" + UUID.randomUUID(), "1000.00");

        postTransfer(request);

        awaitStatus(request.externalReference(), TransferStatus.PAYMENT_PENDING);
        assertThat(stats().debits() - before.debits()).isEqualTo(1);
    }

    @Test
    void processingResumesWhenRabbitMqReturns() throws Exception {
        CreateTransferRequest request = request("merchant-rabbit-" + UUID.randomUUID(), "1000.00");
        rabbitContainer.execInContainer("rabbitmqctl", "stop_app");
        try {
            Thread.sleep(1000);
            ResponseEntity<TransferResponse> created = postTransfer(request);
            assertThat(created.getStatusCode().value()).isEqualTo(202);

            Thread.sleep(5000);
            assertThat(fetch(request.externalReference()).status()).isEqualTo(TransferStatus.ACCEPTED);
        } finally {
            rabbitContainer.execInContainer("rabbitmqctl", "start_app");
        }

        awaitStatus(request.externalReference(), TransferStatus.PAYMENT_PENDING);
    }

    @Test
    void callbackBeforeProviderResponseIsSavedStillCompletesOnce() {
        MoneyStats before = stats();
        faults.replace(false, true, false, false);
        CreateTransferRequest request = request("merchant-early-" + UUID.randomUUID(), "1000.00");

        TransferResponse created = postTransfer(request).getBody();
        ResponseEntity<String> callback = postEvent(new ProviderEventRequest(
                "early-" + created.id(),
                "payment-" + created.id(),
                "SUCCESS"
        ));

        assertThat(callback.getStatusCode().value()).isEqualTo(202);
        awaitStatus(request.externalReference(), TransferStatus.COMPLETED);
        MoneyStats after = stats();
        assertThat(after.debits() - before.debits()).isEqualTo(1);
        assertThat(after.payments() - before.payments()).isEqualTo(1);
    }

    @Test
    void duplicateCallbacksAndRedeliveryCreateNoAdditionalFinancialEffects() {
        CreateTransferRequest request = request("merchant-duplicate-" + UUID.randomUUID(), "1000.00");
        TransferResponse created = postTransfer(request).getBody();
        awaitStatus(request.externalReference(), TransferStatus.PAYMENT_PENDING);
        MoneyStats before = stats();

        rabbitTemplate.convertAndSend(
                RabbitConfig.EXCHANGE,
                RabbitConfig.ROUTING_KEY,
                created.id().toString()
        );
        ResponseEntity<String> callbacks = client.post()
                .uri("/mock/provider/payments/payment-{id}/callbacks", created.id())
                .header("X-Mock-Admin-Key", "local-mock-admin-key")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CallbackCommand("DUPLICATE", "SUCCESS"))
                .retrieve()
                .toEntity(String.class);

        assertThat(callbacks.getStatusCode().value()).isEqualTo(200);
        awaitStatus(request.externalReference(), TransferStatus.COMPLETED);
        assertThat(stats()).isEqualTo(before);
    }

    @Test
    void providerTimeoutStaysUnresolvedUntilAuthoritativeEvidence() {
        faults.replace(false, true, false, false);
        CreateTransferRequest request = request("merchant-unresolved-" + UUID.randomUUID(), "1000.00");
        TransferResponse created = postTransfer(request).getBody();

        awaitStatus(request.externalReference(), TransferStatus.PAYMENT_PENDING);
        assertThat(fetch(request.externalReference()).status())
                .isEqualTo(TransferStatus.PAYMENT_PENDING);

        faults.replace(false, false, false, false);
        ResponseEntity<String> callback = client.post()
                .uri("/mock/provider/payments/payment-{id}/callbacks", created.id())
                .header("X-Mock-Admin-Key", "local-mock-admin-key")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CallbackCommand("ONCE", "SUCCESS"))
                .retrieve()
                .toEntity(String.class);

        assertThat(callback.getStatusCode().value()).isEqualTo(200);
        awaitStatus(request.externalReference(), TransferStatus.COMPLETED);
    }

    @Test
    void confirmedFailureProducesOneRefundDespiteConcurrentRecovery() throws Exception {
        CreateTransferRequest request = request("merchant-refund-" + UUID.randomUUID(), "1000.00");
        TransferResponse created = postTransfer(request).getBody();
        awaitStatus(request.externalReference(), TransferStatus.PAYMENT_PENDING);
        MoneyStats before = stats();
        faults.replace(false, false, true, false);

        CompletableFuture<ResponseEntity<String>> callback = CompletableFuture.supplyAsync(() ->
                postProviderCallback(created.id(), "ONCE", "FAILED")
        );
        await().atMost(Duration.ofSeconds(15)).until(() -> {
            TransferStatus status = fetch(request.externalReference()).status();
            return status == TransferStatus.REFUND_PENDING || status == TransferStatus.REFUNDED;
        });
        if (fetch(request.externalReference()).status() == TransferStatus.REFUND_PENDING) {
            workflow.advance(created.id());
        }
        callback.join();

        TransferResponse refunded = awaitStatus(request.externalReference(), TransferStatus.REFUNDED);
        assertThat(refunded.reconciliationRequired()).isFalse();
        assertThat(stats().refunds() - before.refunds()).isEqualTo(1);
        assertThat(stats().debits() - before.debits()).isZero();
    }

    @Test
    void successCallbackAfterRefundPreservesBothFacts() {
        CreateTransferRequest request = request("merchant-conflict-" + UUID.randomUUID(), "1000.00");
        TransferResponse created = postTransfer(request).getBody();
        awaitStatus(request.externalReference(), TransferStatus.PAYMENT_PENDING);

        ResponseEntity<String> failure = postProviderCallback(created.id(), "ONCE", "FAILED");
        assertThat(failure.getStatusCode().value()).isEqualTo(200);
        awaitStatus(request.externalReference(), TransferStatus.REFUNDED);

        ResponseEntity<String> success = postEvent(new ProviderEventRequest(
                "success-after-refund-" + created.id(),
                "payment-" + created.id(),
                "SUCCESS"
        ));

        assertThat(success.getStatusCode().value()).isEqualTo(202);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            TransferResponse transfer = fetch(request.externalReference());
            assertThat(transfer.status()).isEqualTo(TransferStatus.REFUNDED);
            assertThat(transfer.reconciliationRequired()).isTrue();
        });
    }

    private ResponseEntity<TransferResponse> postTransfer(CreateTransferRequest request) {
        return client.post()
                .uri("/api/v2/transfers")
                .header("X-API-Key", "mock-secret-key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toEntity(TransferResponse.class);
    }

    private TransferResponse fetch(String externalReference) {
        return client.get()
                .uri("/api/v2/transfers/{reference}", externalReference)
                .header("X-API-Key", "mock-secret-key-1")
                .retrieve()
                .body(TransferResponse.class);
    }

    private TransferResponse awaitStatus(String externalReference, TransferStatus expected) {
        await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                assertThat(fetch(externalReference).status()).isEqualTo(expected)
        );
        return fetch(externalReference);
    }

    private ResponseEntity<String> postEvent(ProviderEventRequest event) {
        return client.post()
                .uri("/api/v2/provider/events")
                .header("X-Provider-Secret", "local-provider-secret")
                .contentType(MediaType.APPLICATION_JSON)
                .body(event)
                .retrieve()
                .toEntity(String.class);
    }

    private ResponseEntity<String> postProviderCallback(UUID transferId, String mode, String status) {
        return client.post()
                .uri("/mock/provider/payments/payment-{id}/callbacks", transferId)
                .header("X-Mock-Admin-Key", "local-mock-admin-key")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CallbackCommand(mode, status))
                .retrieve()
                .toEntity(String.class);
    }

    private MoneyStats stats() {
        FineractStats fineract = client.get()
                .uri("/mock/fineract/stats")
                .header("X-Mock-Admin-Key", "local-mock-admin-key")
                .retrieve()
                .body(FineractStats.class);
        ProviderStats provider = client.get()
                .uri("/mock/provider/stats")
                .header("X-Mock-Admin-Key", "local-mock-admin-key")
                .retrieve()
                .body(ProviderStats.class);
        return new MoneyStats(fineract.debitEffects(), fineract.refundEffects(), provider.acceptedPayments());
    }

    private CreateTransferRequest request(String reference, String amount) {
        return new CreateTransferRequest(
                reference,
                SOURCE,
                DESTINATION,
                BANK,
                new BigDecimal(amount),
                "NGN",
                "scenario"
        );
    }

    private record FineractStats(long debitEffects, long refundEffects) {}

    private record ProviderStats(long acceptedPayments) {}

    private record MoneyStats(long debits, long refunds, long payments) {}
}
