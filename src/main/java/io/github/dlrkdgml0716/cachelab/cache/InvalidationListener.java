package io.github.dlrkdgml0716.cachelab.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

@Component
public class InvalidationListener implements MessageListener {
    private final HybridCache cache;
    private final ObjectMapper mapper;
    private final LabMetrics metrics;

    public InvalidationListener(HybridCache cache, ObjectMapper mapper, LabMetrics metrics) {
        this.cache = cache;
        this.mapper = mapper;
        this.metrics = metrics;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        long receivedAt = Invalidation.epochMicros();
        try {
            Invalidation event = mapper.readValue(message.getBody(), Invalidation.class);
            if (event.messageId() == null || event.origin() == null || event.key() != 1) {
                throw new IllegalArgumentException("Invalid invalidation message");
            }
            cache.invalidateLocal(event.key());
            metrics.received(event, receivedAt, Invalidation.epochMicros());
        } catch (Exception error) {
            metrics.count("invalidation.invalid");
        }
    }
}
