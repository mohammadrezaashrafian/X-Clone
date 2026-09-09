package logic_core.infrastructure.cache;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight cache diagnostics (V2.1 #19).
 *
 * <p>Plain atomic counters for cache hits, misses, puts, evictions and errors,
 * exposed as {@code cache.hits}/{@code cache.misses}/{@code cache.puts}/
 * {@code cache.evictions}/{@code cache.errors}. This is deliberately <b>not</b>
 * a full observability platform: it is in-process diagnostics used by tests and
 * operations to confirm cache behavior. The counter names follow the Micrometer
 * convention so these can be exported to Micrometer meters later without
 * renaming.
 */
@Component
public class CacheMetrics
{
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong puts = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();

    public void recordHit()
    {
        hits.incrementAndGet();
    }

    public void recordMiss()
    {
        misses.incrementAndGet();
    }

    public void recordPut()
    {
        puts.incrementAndGet();
    }

    public void recordEviction()
    {
        evictions.incrementAndGet();
    }

    public void recordError()
    {
        errors.incrementAndGet();
    }

    public long hits()
    {
        return hits.get();
    }

    public long misses()
    {
        return misses.get();
    }

    public long puts()
    {
        return puts.get();
    }

    public long evictions()
    {
        return evictions.get();
    }

    public long errors()
    {
        return errors.get();
    }
}