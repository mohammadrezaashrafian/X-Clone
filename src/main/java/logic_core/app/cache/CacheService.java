package logic_core.app.cache;

import java.time.Duration;

/**
 * Application-level cache abstraction (V2.1 #19 Redis Caching Infrastructure).
 *
 * <p>Use cases depend on this interface, never on Redis-specific types or
 * framework annotations. The two implementations are:
 *
 * <ul>
 *   <li>{@code RedisCacheService} — Redis-backed, graceful-failure (all cache
 *       errors fall back to PostgreSQL reads), selected when
 *       {@code app.cache.enabled=true}.</li>
 *   <li>{@code NoopCacheService} — no-op, selected when caching is disabled;
 *       keeps every caller working with zero behavior change.</li>
 * </ul>
 *
 * <p>PostgreSQL is always the source of truth: this cache only stores stable
 * application-level serializations (DTO/projection records) of data that is
 * authoritative in the database.
 */
public interface CacheService
{
    /**
     * Returns the cached JSON value for {@code key}, or {@code null} on a
     * miss or when Redis is unavailable (the caller then loads from
     * PostgreSQL and re-populates the cache).
     */
    String get(String key);

    /**
     * Stores {@code jsonValue} under {@code key} for {@code ttl}. Redis errors
     * are logged and swallowed — they must never corrupt authoritative data.
     */
    void put(String key, String jsonValue, Duration ttl);

    /** Removes one key. Redis errors are logged and swallowed. */
    void evict(String key);

    /**
     * Removes every key matching {@code pattern}. Implementations MUST use a
     * SCAN-based strategy (never the Redis {@code KEYS} command).
     */
    void evictByPattern(String pattern);

    /** Whether cache operations are actually backed by a cache store. */
    boolean isEnabled();
}