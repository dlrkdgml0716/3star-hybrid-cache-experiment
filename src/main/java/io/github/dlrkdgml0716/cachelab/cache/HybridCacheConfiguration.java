package io.github.dlrkdgml0716.cachelab.cache;

import io.github.dlrkdgml0716.cachelab.item.Item;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;
import java.util.concurrent.Callable;

/** Connect Spring's annotation proxy to the lab's existing fenced cache. */
@Configuration
@EnableCaching
public class HybridCacheConfiguration {
    @Bean
    public CacheManager cacheManager(HybridCache hybridCache) {
        var manager = new SimpleCacheManager();
        manager.setCaches(List.of(new SynchronousItemCache(hybridCache)));
        return manager;
    }

    // Only synchronous read-through is exposed. Writes request transaction-aware invalidation inside the service transaction.
    static final class SynchronousItemCache implements Cache {
        private final HybridCache delegate;
        SynchronousItemCache(HybridCache delegate) { this.delegate = delegate; }
        @Override public String getName() { return "items"; }
        @Override public Object getNativeCache() { return delegate; }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Object key, Callable<T> loader) {
            long id = ((Number) key).longValue();
            return (T) delegate.get(id, () -> {
                try { return (Item) loader.call(); }
                catch (Exception error) { throw new ValueRetrievalException(key, loader, error); }
            });
        }

        private UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("Use @Cacheable(sync=true); writes use transaction-aware HybridCache.invalidate");
        }
        @Override public ValueWrapper get(Object key) { throw unsupported(); }
        @Override public <T> T get(Object key, Class<T> type) { throw unsupported(); }
        @Override public void put(Object key, Object value) { throw unsupported(); }
        @Override public void evict(Object key) { throw unsupported(); }
        @Override public void clear() { throw unsupported(); }
    }
}
