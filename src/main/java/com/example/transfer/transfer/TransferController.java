package com.example.transfer.transfer;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.transfer.config.OpenApiConfig;
import com.example.transfer.security.AuthenticatedClient;
import com.example.transfer.transfer.dto.ApiErrorResponse;
import com.example.transfer.transfer.dto.CreateTransferRequest;
import com.example.transfer.transfer.dto.TransferResponse;
import com.example.transfer.transfer.service.CreateTransferResult;
import com.example.transfer.transfer.service.TransferService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v2/transfers")
@Tag(name = "Transfers", description = "Create and look up partner transfers")
@SecurityRequirement(name = OpenApiConfig.API_KEY)
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    @Operation(summary = "Create a transfer or replay an identical request")
    @ApiResponse(responseCode = "202", description = "Transfer accepted for processing")
    @ApiResponse(responseCode = "200", description = "Identical request replayed; the existing transfer is returned")
    @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "401", description = "Missing or unknown API key", content = @Content)
    @ApiResponse(responseCode = "403", description = "Source account is not authorized for this partner", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Same external reference was sent with a different payload", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<TransferResponse> create(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedClient client,
            @Valid @RequestBody CreateTransferRequest request
    ) {
        CreateTransferResult result = transferService.create(
                client.id(),
                request
        );

        HttpStatus status = result.created()
                ? HttpStatus.ACCEPTED
                : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.response());
    }

    @GetMapping("/{reference}")
    @Operation(summary = "Look up a transfer by the partner external reference")
    @ApiResponse(responseCode = "200", description = "Transfer found")
    @ApiResponse(responseCode = "401", description = "Missing or unknown API key", content = @Content)
    @ApiResponse(responseCode = "404", description = "No transfer with this reference for the authenticated partner", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    public ResponseEntity<TransferResponse> getByReference(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedClient client,
            @PathVariable String reference
    ) {
        TransferResponse response = transferService.getByReference(
                client.id(),
                reference
        );

        return ResponseEntity.ok(response);
    }
}
