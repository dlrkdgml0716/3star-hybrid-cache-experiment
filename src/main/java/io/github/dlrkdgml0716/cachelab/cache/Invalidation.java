package io.github.dlrkdgml0716.cachelab.cache;

import java.time.Instant;

public record Invalidation(String messageId, String origin, long key, long sentAtMicros) {
    public static long epochMicros() {
        Instant now = Instant.now();
        return now.getEpochSecond() * 1_000_000L + now.getNano() / 1_000L;
    }
}
