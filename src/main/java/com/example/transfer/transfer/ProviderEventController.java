package com.example.transfer.transfer;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.transfer.transfer.dto.ProviderEventRequest;
import com.example.transfer.transfer.service.ProviderEventService;
import com.example.transfer.transfer.service.TransferWorkflow;

@RestController
@RequestMapping("/api/v2/provider/events")
public class ProviderEventController {

    private static final Logger log = LoggerFactory.getLogger(ProviderEventController.class);

    private final ProviderEventService providerEvents;
    private final TransferWorkflow workflow;

    public ProviderEventController(
            ProviderEventService providerEvents,
            TransferWorkflow workflow
    ) {
        this.providerEvents = providerEvents;
        this.workflow = workflow;
    }

    @PostMapping
    public ResponseEntity<Map<String, String>> receive(
            @RequestBody ProviderEventRequest request
    ) {
        boolean accepted = providerEvents.accept(request);
        if (!accepted) {
            return ResponseEntity.ok(Map.of("status", "duplicate"));
        }

        providerEvents.findPaymentTransferId(request.operationReference())
                .ifPresent(transferId -> {
                    log.info(
                            "Callback for reference {} is advancing transfer {}",
                            request.operationReference(),
                            transferId
                    );
                    workflow.advance(transferId);
                });

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("status", "accepted"));
    }
}
