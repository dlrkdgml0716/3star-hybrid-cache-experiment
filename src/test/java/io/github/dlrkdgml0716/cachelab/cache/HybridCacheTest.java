package io.github.dlrkdgml0716.cachelab.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dlrkdgml0716.cachelab.LabProperties;
import io.github.dlrkdgml0716.cachelab.item.Item;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import java.time.Duration;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HybridCacheTest {
    StringRedisTemplate redis;
    ValueOperations<String, String> values;
    HybridCache cache;
    SimpleMeterRegistry registry;
    ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void prepare() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        registry = new SimpleMeterRegistry();
        LabProperties props = new LabProperties("test", Duration.ofSeconds(60), Duration.ofSeconds(120), 1000, 10);
        cache = new HybridCache(redis, mapper, props, new LabMetrics(registry, props));
    }

    @Test
    void dbMissFillsCachesAndSecondReadUsesL1() {
        Supplier<Item> loader = mock(Supplier.class);
        Item item = new Item(1, 4, "sample");
        when(loader.get()).thenReturn(item);
        assertEquals(item, cache.get(1, loader));
        assertEquals(item, cache.get(1, loader));
        verify(loader, times(1)).get();
        verify(values, times(1)).get(HybridCache.redisKey(1));
        verify(values).set(eq(HybridCache.redisKey(1)), anyString(), eq(Duration.ofSeconds(120)));
    }

    @Test
    void l2HitWarmsL1AndRemoteInvalidationOnlyRemovesL1() throws Exception {
        Item item = new Item(1, 4, "sample");
        when(values.get(HybridCache.redisKey(1))).thenReturn(mapper.writeValueAsString(item));
        Supplier<Item> unused = () -> { throw new AssertionError("DB should not be used"); };
        cache.get(1, unused);
        cache.get(1, unused);
        cache.invalidateLocal(1);
        cache.get(1, unused);
        verify(values, times(2)).get(HybridCache.redisKey(1));
        verify(redis, never()).delete(anyString());
        verify(redis, never()).convertAndSend(anyString(), any());
    }

    @Test
    void redisReadFailureFallsBackAndIsCounted() {
        when(values.get(anyString())).thenThrow(new IllegalStateException("unavailable"));
        assertEquals(7, cache.get(1, () -> new Item(1, 7, "sample")).version());
        assertEquals(1, registry.counter("lab.redis.errors", "operation", "get").count());
    }
}
