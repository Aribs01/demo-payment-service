package com.example.transfer.client;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ClientAccountRepository
        extends JpaRepository<ClientAccount, UUID> {

    boolean existsByClientIdAndAccountNumber(
            UUID clientId,
            String accountNumber
    );
}
