package com.example.transfer.mock.fineract;

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
import com.example.transfer.mock.fineract.MockFineractDtos.AccountResponse;
import com.example.transfer.mock.fineract.MockFineractDtos.OperationRequest;
import com.example.transfer.mock.fineract.MockFineractDtos.OperationResponse;
import com.example.transfer.mock.fineract.MockFineractDtos.StatsResponse;

@RestController
@RequestMapping("/mock/fineract")
@ConditionalOnProperty(
        prefix = "app.mocks",
        name = "enabled",
        havingValue = "true"
)
public class MockFineractController {

    private final MockFineractService service;
    private final MockFineractRepository repository;

    public MockFineractController(
            MockFineractService service,
            MockFineractRepository repository
    ) {
        this.service = service;
        this.repository = repository;
    }

    @PostMapping("/debits")
    public OperationResponse debit(
            @RequestBody OperationRequest request,
            @RequestHeader(
                    value = "X-Mock-Delay-After-Commit",
                    required = false
            ) String delay
    ) {
        OperationResponse result = service.debit(request);
        MockFaults.delayAfterCommit(delay);
        return result;
    }

    @PostMapping("/refunds")
    public OperationResponse refund(
            @RequestBody OperationRequest request,
            @RequestHeader(
                    value = "X-Mock-Delay-After-Commit",
                    required = false
            ) String delay
    ) {
        OperationResponse result = service.refund(request);
        MockFaults.delayAfterCommit(delay);
        return result;
    }

    @GetMapping("/operations/{reference}")
    public ResponseEntity<OperationResponse> operation(
            @PathVariable String reference
    ) {
        return repository.findOperation(reference)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/accounts/{account}")
    public ResponseEntity<AccountResponse> account(
            @PathVariable String account
    ) {
        return repository.findAccount(account)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/stats")
    public StatsResponse stats() {
        return repository.stats();
    }
}
