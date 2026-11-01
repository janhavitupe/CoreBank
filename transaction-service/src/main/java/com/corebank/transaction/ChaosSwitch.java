package com.corebank.transaction;

import com.corebank.common.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Demo-only fault injection, off unless CHAOS_ENABLED is true. Kills the JVM in the worst possible moment:
 * after Account Service has committed the money, before this service has recorded the outcome.
 */
@Component
public class ChaosSwitch {

    private static final Logger log = LoggerFactory.getLogger(ChaosSwitch.class);

    private final boolean enabled;
    private final AtomicBoolean armed = new AtomicBoolean();

    public ChaosSwitch(@Value("${corebank.chaos.enabled}") boolean enabled) {
        this.enabled = enabled;
    }

    public void arm() {
        if (!enabled) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CHAOS_DISABLED", "Fault injection is disabled");
        }
        armed.set(true);
    }

    public void crashIfArmed() {
        if (armed.compareAndSet(true, false)) {
            log.warn("Chaos: halting after Account Service committed, before recording the outcome");
            Runtime.getRuntime().halt(137);
        }
    }
}
