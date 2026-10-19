package com.example.transfer.client;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "clients")
public class Client {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "api_key_hash", nullable = false)
    private String apiKeyHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Client() {}

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }
}
