package com.example.transfer.transfer.service;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.example.transfer.transfer.dto.CreateTransferRequest;

public final class TransferRequestHasher {

    private TransferRequestHasher() {
    }

    public static String hash(CreateTransferRequest request) {
        try {
            ByteArrayOutputStream buffer
                    = new ByteArrayOutputStream();

            DataOutputStream output
                    = new DataOutputStream(buffer);

            write(output, request.externalReference());
            write(output, request.sourceAccount());
            write(output, request.destinationAccount());
            write(output, request.destinationBankCode());
            write(output, request.amount().stripTrailingZeros()
                    .toPlainString());
            write(output, request.currency());
            write(output, request.narration());

            output.flush();

            byte[] digest = MessageDigest
                    .getInstance("SHA-256")
                    .digest(buffer.toByteArray());

            return HexFormat.of().formatHex(digest);

        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "Unable to hash transfer request",
                    exception
            );
        }
    }

    private static void write(
            DataOutputStream output,
            String value
    ) throws IOException {

        if (value == null) {
            output.writeInt(-1);
            return;
        }

        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }
}
