package io.github.dlrkdgml0716.cachelab.api;

import io.github.dlrkdgml0716.cachelab.cache.*;
import io.github.dlrkdgml0716.cachelab.item.*;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
public class LabController {
    private final ItemService service;
    private final HybridCache cache;
    private final LabMetrics metrics;
    private final RedisMessageListenerContainer subscriber;

    public LabController(ItemService service, HybridCache cache, LabMetrics metrics,
                         RedisMessageListenerContainer subscriber) {
        this.service = service;
        this.cache = cache;
        this.metrics = metrics;
        this.subscriber = subscriber;
    }

    @GetMapping("/items/{id}")
    public Item cached(@PathVariable long id) { checkId(id); return service.cached(id); }

    @GetMapping("/items/{id}/db")
    public Item uncached(@PathVariable long id) { checkId(id); return service.uncached(id); }

    @PostMapping("/items/{id}/touch")
    public Item touch(@PathVariable long id) { checkId(id); return service.touch(id); }

    @GetMapping("/experiment/metrics")
    public LabMetrics.Snapshot metrics() {
        return metrics.snapshot(subscriber.isRunning(), subscriber.isListening());
    }

    @GetMapping("/experiment/measurements")
    public LabMetrics.MeasurementSnapshot measurements() { return metrics.measurements(); }

    @PostMapping("/experiment/subscription")
    public synchronized Subscription subscription(@RequestParam boolean enabled) {
        if (enabled) subscriber.start(); else subscriber.stop();
        return new Subscription(subscriber.isRunning(), subscriber.isListening());
    }

    @DeleteMapping("/experiment/cache")
    public void clearCache() { cache.clearExperimentCache(); }

    private void checkId(long id) {
        if (id != 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Lab ID: 1");
    }

    public record Subscription(boolean running, boolean listening) {}
}
