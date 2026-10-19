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

import com.example.transfer.security.AuthenticatedClient;
import com.example.transfer.transfer.dto.CreateTransferRequest;
import com.example.transfer.transfer.dto.TransferResponse;
import com.example.transfer.transfer.service.CreateTransferResult;
import com.example.transfer.transfer.service.TransferService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v2/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> create(
            @AuthenticationPrincipal AuthenticatedClient client,
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
    public ResponseEntity<TransferResponse> getByReference(
            @AuthenticationPrincipal AuthenticatedClient client,
            @PathVariable String reference
    ) {
        TransferResponse response = transferService.getByReference(
                client.id(),
                reference
        );

        return ResponseEntity.ok(response);
    }
}
