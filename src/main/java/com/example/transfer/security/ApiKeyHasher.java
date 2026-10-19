package com.example.transfer.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class ApiKeyHasher {

    private ApiKeyHasher() {
    }

    public static String hash(String apiKey) {
        try {
            MessageDigest digest
                    = MessageDigest.getInstance("SHA-256");

            byte[] bytes = digest.digest(
                    apiKey.getBytes(StandardCharsets.UTF_8)
            );

            return HexFormat.of().formatHex(bytes);

        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    exception
            );
        }
    }
}
