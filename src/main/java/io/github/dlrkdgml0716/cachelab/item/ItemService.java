package io.github.dlrkdgml0716.cachelab.item;

import io.github.dlrkdgml0716.cachelab.cache.HybridCache;
import io.github.dlrkdgml0716.cachelab.cache.Invalidation;
import io.github.dlrkdgml0716.cachelab.cache.LabMetrics;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class ItemService {
    private final ItemRepository repository;
    private final HybridCache cache;
    private final TransactionTemplate transactions;
    private final LabMetrics metrics;

    public ItemService(ItemRepository repository, HybridCache cache, TransactionTemplate transactions,
                       LabMetrics metrics) {
        this.repository = repository;
        this.cache = cache;
        this.transactions = transactions;
        this.metrics = metrics;
    }

    public Item cached(long id) { return cache.get(id, () -> repository.find(id)); }
    public Item uncached(long id) { return repository.find(id); }

    public Item touch(long id) {
        // execute() returns after commit. Do not rely on cache/transaction annotation ordering.
        AtomicLong beforeCommit = new AtomicLong();
        AtomicLong afterCommit = new AtomicLong();
        Item result = transactions.execute(status -> {
            Item updated = repository.touch(id);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) {
                    beforeCommit.set(Invalidation.epochMicros());
                }
                @Override public void afterCommit() {
                    afterCommit.set(Invalidation.epochMicros());
                }
            });
            return updated;
        });
        metrics.write(result.version(), beforeCommit.get(), afterCommit.get());
        cache.invalidate(id);
        return result;
    }
}
