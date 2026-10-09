package io.github.dlrkdgml0716.cachelab.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dlrkdgml0716.cachelab.LabProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.Message;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InvalidationListenerTest {
    @Test
    void recordsRemoteMessageIdentityAndDoesNotRepublish() throws Exception {
        var mapper = new ObjectMapper();
        var props = new LabProperties("reader", Duration.ofSeconds(60), Duration.ofSeconds(120), 100, 10);
        var registry = new SimpleMeterRegistry();
        var metrics = new LabMetrics(registry, props);
        var cache = mock(HybridCache.class);
        var listener = new InvalidationListener(cache, mapper, metrics);
        var event = new Invalidation("message-1", "writer", 1, Invalidation.epochMicros());
        Message message = mock(Message.class);
        when(message.getBody()).thenReturn(mapper.writeValueAsBytes(event));
        listener.onMessage(message, null);
        verify(cache).invalidateLocal(1);
        verify(cache, never()).invalidate(anyLong());
        assertEquals("message-1", metrics.snapshot(true, true).received().get(0).messageId());
        assertEquals(1, metrics.snapshot(true, true).receivedRemoteTotal());
    }

    @Test
    void malformedMessagesAreCountedWithoutEvicting() {
        var mapper = new ObjectMapper();
        var props = new LabProperties("reader", Duration.ofSeconds(60), Duration.ofSeconds(120), 100, 10);
        var registry = new SimpleMeterRegistry();
        var cache = mock(HybridCache.class);
        var listener = new InvalidationListener(cache, mapper, new LabMetrics(registry, props));
        Message message = mock(Message.class);
        when(message.getBody()).thenReturn("{bad".getBytes(StandardCharsets.UTF_8));
        listener.onMessage(message, null);
        verifyNoInteractions(cache);
        assertEquals(1, registry.counter("lab.invalidation.invalid").count());
    }
}
