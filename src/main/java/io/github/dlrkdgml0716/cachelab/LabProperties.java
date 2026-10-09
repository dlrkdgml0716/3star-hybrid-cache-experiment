package io.github.dlrkdgml0716.cachelab;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties("lab")
public record LabProperties(String instanceId, Duration l1Ttl, Duration l2Ttl,
                            long maxEntries, int maxEvents) {
    public LabProperties {
        if (instanceId == null || instanceId.isBlank() || l1Ttl == null || l2Ttl == null
                || l1Ttl.isNegative() || l1Ttl.isZero() || l2Ttl.isNegative()
                || l2Ttl.isZero() || maxEntries < 1 || maxEvents < 1) {
            throw new IllegalArgumentException("Invalid lab configuration");
        }
    }
}
