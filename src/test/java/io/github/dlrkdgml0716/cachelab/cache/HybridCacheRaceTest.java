package io.github.dlrkdgml0716.cachelab.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dlrkdgml0716.cachelab.LabProperties;
import io.github.dlrkdgml0716.cachelab.item.Item;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.aop.support.AopUtils;
import io.github.dlrkdgml0716.cachelab.item.ItemService;
import io.github.dlrkdgml0716.cachelab.item.ItemRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import io.github.dlrkdgml0716.cachelab.cache.RedisValueConfiguration;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real cache instances; controlled scheduling; Redis is an atomic in-memory test double. */
class HybridCacheRaceTest {
    RedisTemplate<String, Object> redis;
    StringRedisTemplate messages;
    ValueOperations<String, Object> values;
    final Map<String, Object> state = new ConcurrentHashMap<>();
    final AtomicReference<Item> database = new AtomicReference<>(new Item(1, 1, "before"));
    final ObjectMapper mapper = new ObjectMapper();
    HybridCache reader;
    HybridCache writer;
    AnnotationConfigApplicationContext context;
    ItemRepository repository;
    ItemService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void init() {
        redis = mock(RedisTemplate.class);
        messages = mock(StringRedisTemplate.class);
        when(redis.getValueSerializer()).thenReturn((RedisSerializer) RedisValueConfiguration.valueSerializer());
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(i -> state.get(i.getArgument(0)));
        when(values.setIfAbsent(anyString(), anyString())).thenAnswer(i ->
                state.putIfAbsent(i.getArgument(0), i.getArgument(1)) == null);
        doAnswer(i -> { state.put(i.getArgument(0), i.getArgument(1)); return null; })
                .when(values).set(anyString(), anyString(), any(Duration.class));
        when(redis.delete(anyString())).thenAnswer(i -> state.remove(i.getArgument(0)) != null);
        when(redis.execute(any(RedisScript.class), any(RedisSerializer.class), any(RedisSerializer.class), anyList(), any(Object[].class))).thenAnswer(i -> {
            List<String> keys = i.getArgument(3);
            synchronized (state) {
                if (i.getArgument(0) == GenerationFence.SAVE) {
                    if (!Objects.equals(state.get(keys.get(0)), decode(i.getArgument(4)))) return 0L;
                    state.put(keys.get(1), decode(i.getArgument(5)));
                } else {
                    state.put(keys.get(0), decode(i.getArgument(4)));
                    state.remove(keys.get(1));
                }
                return 1L;
            }
        });
        reader = instance("reader");
        writer = instance("writer");
        repository = mock(ItemRepository.class);
        when(repository.find(anyLong())).thenAnswer(i -> database.get());
        context = new AnnotationConfigApplicationContext();
        context.register(HybridCacheConfiguration.class);
        context.registerBean(HybridCache.class, () -> reader);
        context.registerBean(ItemService.class, () -> new ItemService(repository, reader, mock(LabMetrics.class)));
        context.refresh();
        service = context.getBean(ItemService.class);
        assertTrue(AopUtils.isAopProxy(service));
    }

    @AfterEach
    void closeContext() { if (context != null) context.close(); }

    HybridCache instance(String name) {
        var props = new LabProperties(name, Duration.ofSeconds(60), Duration.ofSeconds(120), 100, 10);
        return new HybridCache(redis, messages, props, new LabMetrics(new SimpleMeterRegistry(), props));
    }

    @Test
    void oldDatabaseLoadIsRejectedAfterAnotherInstanceInvalidates() throws Exception {
        var paused = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        when(repository.find(1)).thenAnswer(invocation -> {
            Item snapshot = database.get();
            paused.countDown();
            await(resume);
            return snapshot;
        });
        var pending = new FutureTask<>(() -> service.cached(1));
        new Thread(pending).start();
        try {
            await(paused);
            database.set(new Item(1, 2, "after"));
            writer.invalidate(1);
            // No remote message is delivered: L2 protection is independent of Pub/Sub.
        } finally { resume.countDown(); }
        assertEquals(1, pending.get(5, TimeUnit.SECONDS).version());
        assertNull(state.get(HybridCache.redisKey(1)));
        assertEquals(2, service.cached(1).version());
    }

    @Test
    void oldL2ReadIsNotPromotedAfterLocalInvalidation() throws Exception {
        state.put(HybridCache.redisKey(1), database.get());
        var paused = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        when(values.get(HybridCache.redisKey(1))).thenAnswer(i -> {
            Object snapshot = state.get(HybridCache.redisKey(1));
            paused.countDown();
            await(resume);
            return snapshot;
        });
        var pending = new FutureTask<>(() -> service.cached(1));
        new Thread(pending).start();
        try {
            await(paused);
            database.set(new Item(1, 2, "after"));
            writer.invalidate(1);
            reader.invalidateLocal(1); // delivered remote invalidation
        } finally { resume.countDown(); }
        assertEquals(1, pending.get(5, TimeUnit.SECONDS).version());
        assertEquals(2, service.cached(1).version());
    }

    @Test
    void generationReadFailureReturnsDatabaseWithoutCaching() {
        when(values.get(GenerationFence.KEY)).thenThrow(new IllegalStateException("offline"));
        service.cached(1);
        database.set(new Item(1, 2, "after"));
        assertEquals(2, service.cached(1).version());
        assertNull(state.get(HybridCache.redisKey(1)));
    }

    @Test
    void annotationCachesByIdAndUncachedMethodAlwaysReadsDatabase() {
        when(repository.find(2)).thenReturn(new Item(2, 9, "second"));
        assertEquals(1, service.cached(1).version());
        database.set(new Item(1, 2, "after"));
        assertEquals(1, service.cached(1).version());
        assertEquals(2, service.uncached(1).version());
        assertEquals(2, service.uncached(1).version());
        assertEquals(9, service.cached(2).version());
        verify(repository, times(3)).find(1);
        verify(repository).find(2);
    }

    @Test
    void loaderExceptionPropagatesAndIsNotCached() {
        when(repository.find(1)).thenThrow(new IllegalStateException("DB unavailable"))
                .thenReturn(new Item(1, 2, "recovered"));
        assertThrows(IllegalStateException.class, () -> service.cached(1));
        assertEquals(2, service.cached(1).version());
        verify(repository, times(2)).find(1);
    }

    Object decode(byte[] bytes) { return RedisValueConfiguration.valueSerializer().deserialize(bytes); }

    static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS), "Race scheduling timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
