package io.github.dlrkdgml0716.cachelab.cache;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.GenericToStringSerializer;
import java.nio.charset.StandardCharsets;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Lab-only implementation. No application domain or configuration is imported. */
final class GenerationFence {
    static final String KEY = "cache-lab:generation:items";
    static final DefaultRedisScript<Long> SAVE = new DefaultRedisScript<>("""
            local generation = redis.call('GET', KEYS[1])
            if generation ~= ARGV[1] then return 0 end
            redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[3])
            return 1
            """, Long.class);
    static final DefaultRedisScript<Long> REMOVE = new DefaultRedisScript<>("""
            redis.call('SET', KEYS[1], ARGV[1])
            return redis.call('DEL', KEYS[2])
            """, Long.class);
    private final RedisTemplate<String, Object> redis;

    GenerationFence(RedisTemplate<String, Object> redis) { this.redis = redis; }

    Object beforeLoad() {
        Object generation = redis.opsForValue().get(KEY);
        if (generation != null) return generation;
        String candidate = UUID.randomUUID().toString();
        if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(KEY, candidate))) return candidate;
        generation = redis.opsForValue().get(KEY);
        if (generation == null) throw new IllegalStateException("Generation unavailable");
        return generation;
    }

    boolean save(String itemKey, Object value, Object generation, Duration ttl) {
        return Long.valueOf(1).equals(redis.execute(SAVE, RedisSerializer.byteArray(),
                new GenericToStringSerializer<>(Long.class), List.of(KEY, itemKey),
                bytes(generation), bytes(value), Long.toString(ttl.toMillis()).getBytes(StandardCharsets.US_ASCII)));
    }

    void remove(String itemKey) {
        redis.execute(REMOVE, RedisSerializer.byteArray(), new GenericToStringSerializer<>(Long.class),
                List.of(KEY, itemKey), bytes(UUID.randomUUID().toString()));
    }
    @SuppressWarnings("unchecked")
    private byte[] bytes(Object value) {
        return ((RedisSerializer<Object>) redis.getValueSerializer()).serialize(value);
    }
}
