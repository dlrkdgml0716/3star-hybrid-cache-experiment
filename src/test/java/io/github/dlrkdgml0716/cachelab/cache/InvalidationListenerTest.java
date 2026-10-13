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
    void wireBodyIsPlainKeyAndTimestamp() {
        var event = new Invalidation(1, 123456L);
        assertEquals("1|123456", event.body());
        assertEquals(event, Invalidation.parse(event.body()));
    }

    @Test
    void keyOnlyMessageInvalidatesWithoutDelaySample() {
        var props = new LabProperties("reader", Duration.ofSeconds(60), Duration.ofSeconds(120), 100, 10);
        var metrics = new LabMetrics(new SimpleMeterRegistry(), props);
        var cache = mock(HybridCache.class);
        var listener = new InvalidationListener(cache, metrics);
        Message message = mock(Message.class);
        when(message.getBody()).thenReturn("1".getBytes(StandardCharsets.UTF_8));
        listener.onMessage(message, null);
        verify(cache).invalidateLocal(1);
        assertEquals(1, metrics.snapshot(true, true).receivedTotal());
        assertTrue(metrics.snapshot(true, true).received().isEmpty());
    }

    @Test
    void recordsKeyAndTimestampAndDoesNotRepublish() throws Exception {
        var mapper = new ObjectMapper();
        var props = new LabProperties("reader", Duration.ofSeconds(60), Duration.ofSeconds(120), 100, 10);
        var registry = new SimpleMeterRegistry();
        var metrics = new LabMetrics(registry, props);
        var cache = mock(HybridCache.class);
        var listener = new InvalidationListener(cache, metrics);
        var event = new Invalidation(1, Invalidation.epochMicros());
        Message message = mock(Message.class);
        when(message.getBody()).thenReturn(event.body().getBytes(StandardCharsets.UTF_8));
        listener.onMessage(message, null);
        verify(cache).invalidateLocal(1);
        verify(cache, never()).invalidate(anyLong());
        assertEquals(event.sentAtMicros(), metrics.snapshot(true, true).received().get(0).sentAtMicros());
        assertEquals(1, metrics.snapshot(true, true).receivedTotal());
    }

    @Test
    void malformedMessagesAreCountedWithoutEvicting() {
        var mapper = new ObjectMapper();
        var props = new LabProperties("reader", Duration.ofSeconds(60), Duration.ofSeconds(120), 100, 10);
        var registry = new SimpleMeterRegistry();
        var cache = mock(HybridCache.class);
        var listener = new InvalidationListener(cache, new LabMetrics(registry, props));
        Message message = mock(Message.class);
        when(message.getBody()).thenReturn("{bad".getBytes(StandardCharsets.UTF_8));
        listener.onMessage(message, null);
        verifyNoInteractions(cache);
        assertEquals(1, registry.counter("lab.invalidation.invalid").count());
    }
}
