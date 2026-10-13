package io.github.dlrkdgml0716.cachelab.cache;

import io.github.dlrkdgml0716.cachelab.LabProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class LabMetrics {
    private final MeterRegistry registry;
    private final LabProperties props;
    private final String bootId = UUID.randomUUID().toString();
    private final Instant startedAt = Instant.now();
    private final Deque<Published> published = new ArrayDeque<>();
    private final Deque<Received> received = new ArrayDeque<>();
    private final Deque<WriteEvent> writes = new ArrayDeque<>();
    private final Deque<ReadEvent> reads = new ArrayDeque<>();
    private long writeTotal;
    private long readTotal;
    private long discardedWrites;
    private long discardedReads;
    private final AtomicLong publishedTotal = new AtomicLong();
    private final AtomicLong receivedTotal = new AtomicLong();
    private final AtomicLong discarded = new AtomicLong();

    public LabMetrics(MeterRegistry registry, LabProperties props) {
        this.registry = registry;
        this.props = props;
    }

    public void count(String name) { registry.counter("lab." + name).increment(); }
    public void error(String operation) { registry.counter("lab.redis.errors", "operation", operation).increment(); }

    public synchronized void published(Invalidation message, long subscribers) {
        publishedTotal.incrementAndGet();
        count("invalidation.published");
        published.addLast(new Published(message.key(), message.sentAtMicros(), subscribers));
        trim(published);
    }

    public synchronized void received(Invalidation message, long receivedAt, long invalidatedAt) {
        receivedTotal.incrementAndGet();
        count("invalidation.received");
        if (message.sentAtMicros() == null) return; // key-only messages have no delay sample
        long delay = receivedAt - message.sentAtMicros();
        if (delay < 0) count("clock.negative");
        received.addLast(new Received(message.key(),
                message.sentAtMicros(), receivedAt, invalidatedAt, delay >= 0 ? delay : null));
        trim(received);
    }

    public synchronized void write(long version, long beforeCommitMicros, long afterCommitMicros) {
        writes.addLast(new WriteEvent(++writeTotal, version, beforeCommitMicros, afterCommitMicros));
        if (writes.size() > props.maxEvents()) {
            writes.removeFirst();
            discardedWrites++;
        }
    }

    public synchronized void read(long version, String source, long startedMicros, long endedMicros) {
        reads.addLast(new ReadEvent(++readTotal, version, source, startedMicros, endedMicros));
        if (reads.size() > props.maxEvents()) {
            reads.removeFirst();
            discardedReads++;
        }
    }

    public synchronized MeasurementSnapshot measurements() {
        return new MeasurementSnapshot(bootId, writeTotal, readTotal, discardedWrites,
                discardedReads, List.copyOf(writes), List.copyOf(reads));
    }

    private void trim(Deque<?> events) {
        if (events.size() > props.maxEvents()) {
            events.removeFirst();
            discarded.incrementAndGet();
        }
    }

    public synchronized Snapshot snapshot(boolean running, boolean listening) {
        Map<String, Double> redisErrors = Map.of(
                "get", registry.counter("lab.redis.errors", "operation", "get").count(),
                "generation", registry.counter("lab.redis.errors", "operation", "generation").count(),
                "put", registry.counter("lab.redis.errors", "operation", "put").count(),
                "delete", registry.counter("lab.redis.errors", "operation", "delete").count(),
                "publish", registry.counter("lab.redis.errors", "operation", "publish").count());
        return new Snapshot(props.instanceId(), bootId, startedAt, Instant.now(), props.l1Ttl(),
                props.l2Ttl(), running, listening, publishedTotal.get(), receivedTotal.get(),
                discarded.get(), registry.counter("lab.l1.hit").count(),
                registry.counter("lab.l2.hit").count(), registry.counter("lab.db.load").count(),
                redisErrors, registry.counter("lab.invalidation.invalid").count(),
                registry.counter("lab.clock.negative").count(),
                List.copyOf(published), List.copyOf(received));
    }

    public record WriteEvent(long sequence, long version, long beforeCommitMicros, long afterCommitMicros) {}
    public record ReadEvent(long sequence, long version, String source, long startedMicros, long endedMicros) {}
    public record MeasurementSnapshot(String bootId, long writeTotal, long readTotal,
                                      long discardedWrites, long discardedReads,
                                      List<WriteEvent> writes, List<ReadEvent> reads) {}

    public record Published(long key, long sentAtMicros, long subscriberCount) {}
    public record Received(long key, long sentAtMicros,
                           long receivedAtMicros, long invalidatedAtMicros, Long delayMicros) {}
    public record Snapshot(String instanceId, String bootId, Instant startedAt, Instant snapshotAt,
                           java.time.Duration l1Ttl, java.time.Duration l2Ttl,
                           boolean subscriberRunning, boolean subscriberListening,
                           long publishedTotal, long receivedTotal, long discardedEvents,
                           double l1Hits, double l2Hits, double dbLoads,
                           Map<String, Double> redisErrors, double invalidMessages, double negativeClockDelays,
                           List<Published> published, List<Received> received) {}
}
