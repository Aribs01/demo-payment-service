package com.example.transfer.transfer.service;

import com.example.transfer.client.ClientAccountRepository;
import com.example.transfer.transfer.Transfer;
import com.example.transfer.transfer.dto.CreateTransferRequest;
import com.example.transfer.transfer.dto.TransferResponse;
import com.example.transfer.transfer.repository.TransferRepository;
import com.example.transfer.transfer.service.TransferCreationService.StoredTransfer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final ClientAccountRepository clientAccountRepository;
    private final TransferCreationService creationService;
    private final TransferRepository transferRepository;

    public TransferService(
            ClientAccountRepository clientAccountRepository,
            TransferCreationService creationService,
            TransferRepository transferRepository
    ) {
        this.clientAccountRepository = clientAccountRepository;
        this.creationService = creationService;
        this.transferRepository = transferRepository;
    }

    public CreateTransferResult create(
            UUID clientId,
            CreateTransferRequest request
    ) {
        boolean authorized = clientAccountRepository
                .existsByClientIdAndAccountNumber(
                        clientId,
                        request.sourceAccount()
                );

        if (!authorized) {
            log.warn(
                    "Client {} is not authorized to debit source account {}",
                    clientId,
                    request.sourceAccount()
            );
            throw new SourceAccountNotAuthorizedException();
        }

        StoredTransfer stored = creationService.createOrGet(
                clientId,
                request
        );

        return new CreateTransferResult(
                TransferResponse.from(stored.transfer()),
                stored.created()
        );
    }

    public TransferResponse getByReference(
            UUID clientId,
            String externalReference
    ) {
        Transfer transfer = transferRepository
                .findByClientIdAndExternalReference(
                        clientId,
                        externalReference
                )
                .orElseThrow(TransferNotFoundException::new);

        return TransferResponse.from(transfer);
    }
}
