package io.github.dlrkdgml0716.cachelab.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.dlrkdgml0716.cachelab.LabProperties;
import io.github.dlrkdgml0716.cachelab.item.Item;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.function.Supplier;

@Component
public class HybridCache {
    public static final String CHANNEL = "cache-lab.invalidate.items";
    private final Cache<String, Item> l1;
    private final RedisTemplate<String, Object> redis;
    private final StringRedisTemplate messages;
    private final LabProperties props;
    private final LabMetrics metrics;
    private final GenerationFence fence;
    private final Object localMonitor = new Object();
    private long localGeneration;
    private final Object[] loadLocks = new Object[64];

    public HybridCache(RedisTemplate<String, Object> redis, StringRedisTemplate messages, LabProperties props, LabMetrics metrics) {
        this.redis = redis;
        this.messages = messages;
        this.props = props;
        this.metrics = metrics;
        this.fence = new GenerationFence(redis);
        this.l1 = Caffeine.newBuilder().maximumSize(props.maxEntries()).expireAfterWrite(props.l1Ttl()).build();
        java.util.Arrays.setAll(loadLocks, index -> new Object());
    }

    public Item get(long id, Supplier<Item> loader) {
        String key = Long.toString(id);
        synchronized (loadLocks[(key.hashCode() & Integer.MAX_VALUE) % loadLocks.length]) {
            return load(id, key, loader);
        }
    }

    private Item load(long id, String key, Supplier<Item> loader) {
        long started = Invalidation.epochMicros();
        long local = generation();
        Item cached = l1.getIfPresent(key);
        if (cached != null) {
            metrics.count("l1.hit");
            metrics.read(cached.version(), "L1", started, Invalidation.epochMicros());
            return cached;
        }
        try {
            Object value = redis.opsForValue().get(redisKey(id));
            if (value != null) {
                Item shared = (Item) value;
                if (shared.id() != id) throw new IllegalStateException("L2 key mismatch");
                promote(key, shared, local);
                metrics.count("l2.hit");
                metrics.read(shared.version(), "L2", started, Invalidation.epochMicros());
                return shared;
            }
        } catch (Exception error) { metrics.error("get"); }

        Object expected = null;
        local = generation();
        try { expected = fence.beforeLoad(); }
        catch (Exception error) { metrics.error("generation"); }
        metrics.count("db.load");
        Item loaded = loader.get();
        if (expected != null) {
            try {
                if (fence.save(redisKey(id), loaded, expected, props.l2Ttl())) {
                    promote(key, loaded, local);
                } else { metrics.count("fill.rejected"); }
            } catch (Exception error) { metrics.error("put"); }
        }
        metrics.read(loaded.version(), "DB", started, Invalidation.epochMicros());
        return loaded;
    }

    public void invalidate(long id) {
        runAfterCommitOrNow(() -> invalidateNow(id));
    }

    private void runAfterCommitOrNow(Runnable action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
        } else {
            action.run();
        }
    }

    private void invalidateNow(long id) {
        invalidateLocal(id);
        try { fence.remove(redisKey(id)); }
        catch (Exception error) { metrics.error("delete"); }
        finally { invalidateLocal(id); }
        try {
            Invalidation message = new Invalidation(id, Invalidation.epochMicros());
            Long subscribers = messages.convertAndSend(CHANNEL, message.body());
            metrics.published(message, subscribers == null ? -1 : subscribers);
        } catch (Exception error) { metrics.error("publish"); }
    }

    public void invalidateLocal(long id) {
        synchronized (localMonitor) {
            localGeneration++;
            l1.invalidate(Long.toString(id));
        }
    }

    private long generation() {
        synchronized (localMonitor) { return localGeneration; }
    }

    private void promote(String key, Item value, long expected) {
        synchronized (localMonitor) {
            if (localGeneration == expected) l1.put(key, value);
        }
    }

    public void clearExperimentCache() {
        // The reset endpoint is defined for the single experiment item, not arbitrary records.
        invalidateLocal(1);
        try { fence.remove(redisKey(1)); }
        finally { invalidateLocal(1); }
    }

    static String redisKey(long id) { return "cache-lab:items:" + id; }
}
