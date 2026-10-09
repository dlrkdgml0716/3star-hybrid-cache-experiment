package io.github.dlrkdgml0716.cachelab.item;

import io.github.dlrkdgml0716.cachelab.cache.HybridCache;
import io.github.dlrkdgml0716.cachelab.cache.LabMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ItemServiceTest {
    @Test
    void invalidationRunsAfterSuccessfulCommitAndNotOnRollback() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE lab_item(id BIGINT PRIMARY KEY, version BIGINT NOT NULL, item_value VARCHAR(255))");
        jdbc.update("INSERT INTO lab_item VALUES(1, 0, 'sample')");
        var repository = new ItemRepository(jdbc);
        var cache = mock(HybridCache.class);
        doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(1, repository.find(1).version());
            return null;
        }).when(cache).invalidate(1);
        var metrics = mock(LabMetrics.class);
        var service = new ItemService(repository, cache,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), metrics);
        assertEquals(1, service.touch(1).version());
        verify(cache).invalidate(1);
        verify(metrics).write(eq(1L), longThat(value -> value > 0), longThat(value -> value > 0));
        assertThrows(RuntimeException.class, () -> service.touch(2));
        verify(cache, never()).invalidate(2);
    }
}
