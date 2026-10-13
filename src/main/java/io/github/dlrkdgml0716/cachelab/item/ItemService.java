package io.github.dlrkdgml0716.cachelab.item;

import io.github.dlrkdgml0716.cachelab.cache.HybridCache;
import io.github.dlrkdgml0716.cachelab.cache.Invalidation;
import io.github.dlrkdgml0716.cachelab.cache.LabMetrics;
import org.springframework.stereotype.Service;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class ItemService {
    private final ItemRepository repository;
    private final HybridCache cache;
    private final LabMetrics metrics;

    public ItemService(ItemRepository repository, HybridCache cache, LabMetrics metrics) {
        this.repository = repository;
        this.cache = cache;
        this.metrics = metrics;
    }

    @Cacheable(cacheNames = "items", key = "#id", sync = true)
    public Item cached(long id) { return repository.find(id); }
    public Item uncached(long id) { return repository.find(id); }

    @Transactional
    public Item touch(long id) {
        Item updated = repository.touch(id);
        AtomicLong beforeCommit = new AtomicLong();
        // Record the committed write even when this method joins an outer transaction.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void beforeCommit(boolean readOnly) {
                beforeCommit.set(Invalidation.epochMicros());
            }
            @Override public void afterCommit() {
                metrics.write(updated.version(), beforeCommit.get(), Invalidation.epochMicros());
            }
        });
        // Request inside the transaction; HybridCache defers deletion and publication.
        cache.invalidate(id);
        return updated;
    }
}
