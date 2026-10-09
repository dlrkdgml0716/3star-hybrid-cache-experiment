package io.github.dlrkdgml0716.cachelab.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.dlrkdgml0716.cachelab.LabProperties;
import io.github.dlrkdgml0716.cachelab.item.Item;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.util.UUID;
import java.util.function.Supplier;

@Component
public class HybridCache {
    public static final String CHANNEL = "cache-lab.invalidate.items";
    private final Cache<Long, Item> l1;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final LabProperties props;
    private final LabMetrics metrics;

    public HybridCache(StringRedisTemplate redis, ObjectMapper mapper, LabProperties props, LabMetrics metrics) {
        this.redis = redis;
        this.mapper = mapper;
        this.props = props;
        this.metrics = metrics;
        this.l1 = Caffeine.newBuilder().maximumSize(props.maxEntries()).expireAfterWrite(props.l1Ttl()).build();
    }

    public Item get(long id, Supplier<Item> loader) {
        long started = Invalidation.epochMicros();
        Item local = l1.getIfPresent(id);
        if (local != null) {
            metrics.count("l1.hit");
            metrics.read(local.version(), "L1", started, Invalidation.epochMicros());
            return local;
        }
        try {
            String serialized = redis.opsForValue().get(redisKey(id));
            if (serialized != null) {
                Item shared = mapper.readValue(serialized, Item.class);
                if (shared.id() != id) throw new IllegalStateException("L2 key mismatch");
                l1.put(id, shared);
                metrics.count("l2.hit");
                metrics.read(shared.version(), "L2", started, Invalidation.epochMicros());
                return shared;
            }
        } catch (Exception error) {
            metrics.error("get");
        }
        metrics.count("db.load");
        Item loaded = loader.get();
        l1.put(id, loaded);
        try {
            redis.opsForValue().set(redisKey(id), mapper.writeValueAsString(loaded), props.l2Ttl());
        } catch (Exception error) {
            metrics.error("put");
        }
        metrics.read(loaded.version(), "DB", started, Invalidation.epochMicros());
        return loaded;
    }

    public void invalidate(long id) {
        l1.invalidate(id);
        try { redis.delete(redisKey(id)); } catch (Exception error) { metrics.error("delete"); }
        try {
            Invalidation message = new Invalidation(UUID.randomUUID().toString(),
                    props.instanceId(), id, Invalidation.epochMicros());
            Long subscribers = redis.convertAndSend(CHANNEL, mapper.writeValueAsString(message));
            metrics.published(message, subscribers == null ? -1 : subscribers);
        } catch (Exception error) {
            metrics.error("publish");
        }
    }

    public void invalidateLocal(long id) { l1.invalidate(id); }

    public void clearExperimentCache() {
        // Only this lab's single known key, never Redis FLUSHDB/KEYS.
        l1.invalidateAll();
        redis.delete(redisKey(1));
    }

    static String redisKey(long id) { return "cache-lab:items:" + id; }
}
