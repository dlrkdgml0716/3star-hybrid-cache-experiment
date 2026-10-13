package io.github.dlrkdgml0716.cachelab.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dlrkdgml0716.cachelab.LabProperties;
import io.github.dlrkdgml0716.cachelab.item.Item;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import io.github.dlrkdgml0716.cachelab.cache.RedisValueConfiguration;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import java.time.Duration;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HybridCacheTest {
    RedisTemplate<String, Object> redis;
    StringRedisTemplate messages;
    ValueOperations<String, Object> values;
    HybridCache cache;
    SimpleMeterRegistry registry;
    ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void prepare() {
        redis = mock(RedisTemplate.class);
        messages = mock(StringRedisTemplate.class);
        when(redis.getValueSerializer()).thenReturn((RedisSerializer) RedisValueConfiguration.valueSerializer());
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString())).thenReturn(true);
        when(redis.execute(any(RedisScript.class), any(RedisSerializer.class), any(RedisSerializer.class), anyList(), any(Object[].class))).thenReturn(1L);
        registry = new SimpleMeterRegistry();
        LabProperties props = new LabProperties("test", Duration.ofSeconds(60), Duration.ofSeconds(120), 1000, 10);
        cache = new HybridCache(redis, messages, props, new LabMetrics(registry, props));
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
        verify(redis).execute(eq(GenerationFence.SAVE), any(RedisSerializer.class), any(RedisSerializer.class), eq(java.util.List.of(GenerationFence.KEY, HybridCache.redisKey(1))), any(byte[].class), any(byte[].class), argThat((byte[] ttl) -> java.util.Arrays.equals(ttl, "120000".getBytes(java.nio.charset.StandardCharsets.US_ASCII))));
    }

    @Test
    void l2HitWarmsL1AndRemoteInvalidationOnlyRemovesL1() throws Exception {
        Item item = new Item(1, 4, "sample");
        when(values.get(HybridCache.redisKey(1))).thenReturn(item);
        Supplier<Item> unused = () -> { throw new AssertionError("DB should not be used"); };
        cache.get(1, unused);
        cache.get(1, unused);
        cache.invalidateLocal(1);
        cache.get(1, unused);
        verify(values, times(2)).get(HybridCache.redisKey(1));
        verify(redis, never()).delete(anyString());
        verify(messages, never()).convertAndSend(anyString(), any());
    }

    @Test
    void redisReadFailureFallsBackAndIsCounted() {
        when(values.get(anyString())).thenThrow(new IllegalStateException("unavailable"));
        assertEquals(7, cache.get(1, () -> new Item(1, 7, "sample")).version());
        assertEquals(1, registry.counter("lab.redis.errors", "operation", "get").count());
    }

    @Test
    void generationReadFailureIsVisibleInSnapshotAndSkipsCacheFill() {
        when(values.get(GenerationFence.KEY)).thenThrow(new IllegalStateException("generation unavailable"));
        assertEquals(7, cache.get(1, () -> new Item(1, 7, "sample")).version());
        assertEquals(8, cache.get(1, () -> new Item(1, 8, "sample")).version());
        verify(redis, never()).execute(any(RedisScript.class), any(RedisSerializer.class),
                any(RedisSerializer.class), anyList(), any(Object[].class));
        var props = new LabProperties("test", Duration.ofSeconds(60), Duration.ofSeconds(120), 1000, 10);
        var snapshot = new LabMetrics(registry, props).snapshot(true, true);
        assertEquals(2.0, snapshot.redisErrors().get("generation"));
        assertEquals(0.0, snapshot.redisErrors().get("get"));
    }

    @Test
    void generationMismatchRejectsFillWithoutCountingRedisError() {
        when(redis.execute(eq(GenerationFence.SAVE), any(RedisSerializer.class),
                any(RedisSerializer.class), anyList(), any(Object[].class))).thenReturn(0L);
        assertEquals(7, cache.get(1, () -> new Item(1, 7, "sample")).version());
        assertEquals(1.0, registry.counter("lab.fill.rejected").count());
        assertEquals(0.0, registry.counter("lab.redis.errors", "operation", "generation").count());
        assertEquals(0.0, registry.counter("lab.redis.errors", "operation", "put").count());
    }
}
