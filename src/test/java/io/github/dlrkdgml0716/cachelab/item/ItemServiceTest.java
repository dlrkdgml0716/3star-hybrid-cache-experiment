package io.github.dlrkdgml0716.cachelab.item;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dlrkdgml0716.cachelab.LabProperties;
import io.github.dlrkdgml0716.cachelab.cache.HybridCache;
import io.github.dlrkdgml0716.cachelab.cache.HybridCacheConfiguration;
import io.github.dlrkdgml0716.cachelab.cache.LabMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import io.github.dlrkdgml0716.cachelab.cache.RedisValueConfiguration;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ItemServiceTest {
    @Configuration
    @EnableTransactionManagement
    static class Transactions { }

    AnnotationConfigApplicationContext context;
    DriverManagerDataSource dataSource;
    ItemRepository repository;
    ItemService service;
    HybridCache cache;
    RedisTemplate<String, Object> redis;
    StringRedisTemplate messages;
    LabMetrics metrics;
    TransactionTemplate outer;
    AtomicInteger deletions;
    long expectedCommittedVersion;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void prepare() {
        dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE lab_item(id BIGINT PRIMARY KEY, version BIGINT NOT NULL, item_value VARCHAR(255))");
        jdbc.update("INSERT INTO lab_item VALUES(1, 0, 'sample')");
        repository = new ItemRepository(jdbc);
        var manager = new DataSourceTransactionManager(dataSource);
        outer = new TransactionTemplate(manager);
        redis = mock(RedisTemplate.class);
        messages = mock(StringRedisTemplate.class);
        when(redis.getValueSerializer()).thenReturn((RedisSerializer) RedisValueConfiguration.valueSerializer());
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString())).thenReturn(true);
        deletions = new AtomicInteger();
        expectedCommittedVersion = 1;
        when(redis.execute(any(RedisScript.class), any(RedisSerializer.class), any(RedisSerializer.class), anyList(), any(Object[].class))).thenAnswer(invocation -> {
            RedisScript<?> script = invocation.getArgument(0);
            if (script.getScriptAsString().contains("'DEL'")) {
                // An independent connection proves the DB commit has actually happened.
                assertEquals(expectedCommittedVersion, committedVersion());
                deletions.incrementAndGet();
            }
            return 1L;
        });
        metrics = mock(LabMetrics.class);
        var props = new LabProperties("test", Duration.ofSeconds(60), Duration.ofSeconds(120), 100, 10);
        cache = new HybridCache(redis, messages, props, metrics);
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class, HybridCacheConfiguration.class);
        context.registerBean("transactionManager", DataSourceTransactionManager.class, () -> manager);
        context.registerBean(HybridCache.class, () -> cache);
        context.registerBean(ItemService.class, () -> new ItemService(repository, cache, metrics));
        context.refresh();
        service = context.getBean(ItemService.class);
        assertTrue(AopUtils.isAopProxy(service));
        assertEquals(0, service.cached(1).version());
        clearInvocations(redis, messages, metrics);
    }

    @AfterEach
    void close() { if (context != null) context.close(); }

    long committedVersion() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT version FROM lab_item WHERE id = 1")) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    void assertNotInvalidated() {
        assertEquals(0, deletions.get());
        verify(messages, never()).convertAndSend(anyString(), any());
        verify(metrics, never()).write(anyLong(), anyLong(), anyLong());
        assertEquals(0, service.cached(1).version()); // The primed L1 is still present.
    }

    void assertInvalidated() {
        assertEquals(1, deletions.get());
        verify(messages).convertAndSend(eq(HybridCache.CHANNEL), any());
        verify(metrics).write(eq(1L), longThat(value -> value > 0), longThat(value -> value > 0));
        assertEquals(1, service.cached(1).version());
    }

    @Test
    void standaloneWriteCommitsBeforeDeletionAndPublication() {
        assertEquals(1, service.touch(1).version());
        assertInvalidated();
    }

    @Test
    void joinedWriteWaitsForOuterCommitEvenAfterServiceReturns() {
        outer.executeWithoutResult(status -> {
            assertEquals(1, service.touch(1).version());
            assertNotInvalidated();
        });
        assertInvalidated();
    }

    @Test
    void outerExceptionRollsBackWithoutDeletingOrPublishing() throws Exception {
        assertThrows(IllegalStateException.class, () -> outer.executeWithoutResult(status -> {
            service.touch(1);
            assertNotInvalidated();
            throw new IllegalStateException("rollback");
        }));
        assertEquals(0, committedVersion());
        assertNotInvalidated();
    }

    @Test
    void rollbackOnlyDoesNotDeletePublishOrRecordCommittedWrite() throws Exception {
        outer.executeWithoutResult(status -> {
            service.touch(1);
            status.setRollbackOnly();
            assertNotInvalidated();
        });
        assertEquals(0, committedVersion());
        assertNotInvalidated();
    }

    @Test
    void noTransactionInvalidatesImmediately() {
        expectedCommittedVersion = 0;
        cache.invalidate(1);
        assertEquals(1, deletions.get());
        verify(messages).convertAndSend(eq(HybridCache.CHANNEL), any());
        assertEquals(7, cache.get(1, () -> new Item(1, 7, "reloaded")).version());
    }

    @Test
    void failedWriteDoesNotRequestInvalidation() {
        assertThrows(RuntimeException.class, () -> service.touch(2));
        assertNotInvalidated();
    }
}
