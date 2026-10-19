package com.example.transfer.mock.fault;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/mock/faults")
@ConditionalOnProperty(
        prefix = "app.mocks",
        name = "enabled",
        havingValue = "true"
)
public class MockFaultController {

    private static final Logger log = LoggerFactory.getLogger(MockFaultController.class);

    private final FaultFlags flags;

    public MockFaultController(FaultFlags flags) {
        this.flags = flags;
    }

    @GetMapping
    public Map<String, Boolean> current() {
        return flags.snapshot();
    }

    @PutMapping
    public Map<String, Boolean> replace(@RequestBody FaultSettings settings) {
        flags.replace(
                settings.fineractDelayAfterCommit(),
                settings.providerDelayAfterCommit(),
                settings.refundDelayAfterCommit(),
                settings.crashAfterDebit()
        );
        log.info("Updated simulated fault flags {}", flags.snapshot());
        return flags.snapshot();
    }

    public record FaultSettings(
            boolean fineractDelayAfterCommit,
            boolean providerDelayAfterCommit,
            boolean refundDelayAfterCommit,
            boolean crashAfterDebit
            ) {}
}
