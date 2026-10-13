package io.github.dlrkdgml0716.cachelab.cache;

import java.nio.charset.StandardCharsets;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

@Component
public class InvalidationListener implements MessageListener {
    private final HybridCache cache;
    private final LabMetrics metrics;

    public InvalidationListener(HybridCache cache, LabMetrics metrics) {
        this.cache = cache;
        this.metrics = metrics;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        long receivedAt = Invalidation.epochMicros();
        try {
            Invalidation event = Invalidation.parse(new String(message.getBody(), StandardCharsets.UTF_8));
            if (event.key() < 1) {
                throw new IllegalArgumentException("Invalid invalidation message");
            }
            cache.invalidateLocal(event.key());
            metrics.received(event, receivedAt, Invalidation.epochMicros());
        } catch (Exception error) {
            metrics.count("invalidation.invalid");
        }
    }
}
