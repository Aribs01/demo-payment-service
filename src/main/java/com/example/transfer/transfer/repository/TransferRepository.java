package com.example.transfer.transfer.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.transfer.transfer.Transfer;

public interface TransferRepository
        extends JpaRepository<Transfer, UUID> {

    Optional<Transfer> findByClientIdAndExternalReference(
            UUID clientId,
            String externalReference
    );
}
