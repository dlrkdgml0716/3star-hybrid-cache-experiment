package io.github.dlrkdgml0716.cachelab.cache;

import java.time.Instant;

public record Invalidation(long key, Long sentAtMicros) {
    public String body() { return sentAtMicros == null ? Long.toString(key) : key + "|" + sentAtMicros; }

    public static Invalidation parse(String body) {
        int separator = body.lastIndexOf('|');
        if (separator < 0) return new Invalidation(Long.parseLong(body), null);
        return new Invalidation(Long.parseLong(body.substring(0, separator)),
                Long.parseLong(body.substring(separator + 1)));
    }

    public static long epochMicros() {
        Instant now = Instant.now();
        return now.getEpochSecond() * 1_000_000L + now.getNano() / 1_000L;
    }
}
