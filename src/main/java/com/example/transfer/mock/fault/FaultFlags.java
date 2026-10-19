package com.example.transfer.mock.fault;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Component;

@Component
public class FaultFlags {

    private final AtomicBoolean fineractDelayAfterCommit = new AtomicBoolean();
    private final AtomicBoolean providerDelayAfterCommit = new AtomicBoolean();
    private final AtomicBoolean refundDelayAfterCommit = new AtomicBoolean();
    private final AtomicBoolean crashAfterDebit = new AtomicBoolean();

    public boolean fineractDelayAfterCommit() {
        return fineractDelayAfterCommit.get();
    }

    public boolean providerDelayAfterCommit() {
        return providerDelayAfterCommit.get();
    }

    public boolean refundDelayAfterCommit() {
        return refundDelayAfterCommit.get();
    }

    public boolean consumeCrashAfterDebit() {
        return crashAfterDebit.compareAndSet(true, false);
    }

    public void replace(
            boolean fineractDelay,
            boolean providerDelay,
            boolean refundDelay,
            boolean crashAfterDebit
    ) {
        this.fineractDelayAfterCommit.set(fineractDelay);
        this.providerDelayAfterCommit.set(providerDelay);
        this.refundDelayAfterCommit.set(refundDelay);
        this.crashAfterDebit.set(crashAfterDebit);
    }

    public Map<String, Boolean> snapshot() {
        Map<String, Boolean> flags = new LinkedHashMap<>();
        flags.put("fineractDelayAfterCommit", fineractDelayAfterCommit.get());
        flags.put("providerDelayAfterCommit", providerDelayAfterCommit.get());
        flags.put("refundDelayAfterCommit", refundDelayAfterCommit.get());
        flags.put("crashAfterDebit", crashAfterDebit.get());
        return flags;
    }
}
