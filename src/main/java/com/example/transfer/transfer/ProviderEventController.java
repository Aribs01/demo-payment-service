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

import com.example.transfer.config.OpenApiConfig;
import com.example.transfer.transfer.dto.ApiErrorResponse;
import com.example.transfer.transfer.dto.ProviderEventRequest;
import com.example.transfer.transfer.dto.ProviderEventResponse;
import com.example.transfer.transfer.service.ProviderEventService;
import com.example.transfer.transfer.service.TransferWorkflow;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/v2/provider/events")
@Tag(name = "Provider callbacks", description = "Payment provider status callbacks")
@SecurityRequirement(name = OpenApiConfig.PROVIDER_SECRET)
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
    @Operation(summary = "Receive a provider callback")
    @ApiResponse(responseCode = "202", description = "Event stored", content = @Content(schema = @Schema(implementation = ProviderEventResponse.class)))
    @ApiResponse(responseCode = "200", description = "Duplicate event ignored", content = @Content(schema = @Schema(implementation = ProviderEventResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid event", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "Missing or wrong provider secret", content = @Content)
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
