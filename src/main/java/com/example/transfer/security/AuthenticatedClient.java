package com.example.transfer.security;

import java.util.UUID;

public record AuthenticatedClient(
        UUID id,
        String name
        ) {}
