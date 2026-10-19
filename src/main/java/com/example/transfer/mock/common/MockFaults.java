package com.example.transfer.mock.common;

public final class MockFaults {

    private MockFaults() {
    }

    public static void delayAfterCommit(String header) {
        if (!"true".equalsIgnoreCase(header)) {
            return;
        }

        try {
            Thread.sleep(5000);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Simulated response delay interrupted",
                    exception
            );
        }
    }
}
