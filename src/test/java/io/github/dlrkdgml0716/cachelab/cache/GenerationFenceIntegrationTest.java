package io.github.dlrkdgml0716.cachelab.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Dedicated disposable localhost Redis required: this lab's generation key is mutated. */
@EnabledIfEnvironmentVariable(named = "CACHE_TEST_REDIS_PORT", matches = "[0-9]+")
class GenerationFenceIntegrationTest {
    @Test
    void realRedisExecutesCompareAndSetWithExpiry() {
        var factory = new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(System.getenv("CACHE_TEST_REDIS_PORT")));
        factory.afterPropertiesSet();
        var redis = new RedisValueConfiguration().cacheRedisTemplate(factory);
        redis.afterPropertiesSet();
        String key = "cache-lab:integration:" + java.util.UUID.randomUUID();
        try {
            var first = new GenerationFence(redis);
            var second = new GenerationFence(redis);
            Object old = first.beforeLoad();
            assertTrue(first.save(key, "before", old, Duration.ofSeconds(30)));
            second.remove(key);
            assertFalse(first.save(key, "stale", old, Duration.ofSeconds(30)));
            assertNull(redis.opsForValue().get(key));
            assertTrue(first.save(key, "after", first.beforeLoad(), Duration.ofSeconds(30)));
            assertEquals("after", redis.opsForValue().get(key));
            assertTrue(redis.getExpire(key) > 0);
        } finally {
            redis.delete(List.of(key, GenerationFence.KEY));
            factory.destroy();
        }
    }
}
