package com.example.transfer.mock.provider;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.transfer.mock.common.MockFaults;
import com.example.transfer.mock.provider.MockProviderDtos.CallbackCommand;
import com.example.transfer.mock.provider.MockProviderDtos.PaymentRequest;
import com.example.transfer.mock.provider.MockProviderDtos.PaymentResponse;
import com.example.transfer.mock.provider.MockProviderDtos.SettleRequest;
import com.example.transfer.mock.provider.MockProviderDtos.StatsResponse;

@RestController
@RequestMapping("/mock/provider")
@ConditionalOnProperty(
        prefix = "app.mocks",
        name = "enabled",
        havingValue = "true"
)
public class MockProviderController {

    private final MockProviderService service;
    private final MockProviderRepository repository;
    private final MockProviderCallbackSender callbacks;

    public MockProviderController(
            MockProviderService service,
            MockProviderRepository repository,
            MockProviderCallbackSender callbacks
    ) {
        this.service = service;
        this.repository = repository;
        this.callbacks = callbacks;
    }

    @PostMapping("/payments")
    public PaymentResponse submit(
            @RequestBody PaymentRequest request,
            @RequestHeader(
                    value = "X-Mock-Delay-After-Commit",
                    required = false
            ) String delay
    ) {
        PaymentResponse response = service.submit(request);
        MockFaults.delayAfterCommit(delay);
        return response;
    }

    @GetMapping("/payments/{reference}")
    public ResponseEntity<PaymentResponse> find(
            @PathVariable String reference
    ) {
        return repository.find(reference)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/payments/{reference}/settle")
    public PaymentResponse settle(
            @PathVariable String reference,
            @RequestBody SettleRequest request
    ) {
        return service.settle(reference, request);
    }

    @PostMapping("/payments/{reference}/callbacks")
    public PaymentResponse sendCallback(
            @PathVariable String reference,
            @RequestBody(required = false) CallbackCommand command
    ) {
        callbacks.send(reference, command);
        return repository.find(reference)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found"));
    }

    @GetMapping("/stats")
    public StatsResponse stats() {
        return repository.stats();
    }
}
