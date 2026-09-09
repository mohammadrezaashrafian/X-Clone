package logic_core.infrastructure.cache;

import logic_core.app.cache.CacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/**
 * Redis-backed {@link CacheService} (V2.1 #19).
 *
 * <p><b>Graceful failure</b> — every operation is wrapped: on any Redis error
 * the operation degrades to a miss/no-op and returns without throwing, so a
 * Redis outage never breaks application reads (they fall back to PostgreSQL)
 * and never corrupts authoritative data. Errors are counted in
 * {@link CacheMetrics} and logged at WARN.
 *
 * <p><b>Pattern eviction</b> — {@link #evictByPattern(String)} uses a SCAN-based
 * strategy ({@code SCAN} cursor over matching keys, then {@code DEL}), never the
 * blocking {@code KEYS} command, so production is safe on large key spaces.
 *
 * <p>Values are opaque JSON strings produced by {@code CacheJsonCodec}; this
 * class never sees domain or JPA types.
 */
public class RedisCacheService implements CacheService
{
    private static final Logger log = LoggerFactory.getLogger(RedisCacheService.class);

    private static final int SCAN_BATCH_SIZE = 200;

    private final StringRedisTemplate redis;
    private final CacheMetrics metrics;

    public RedisCacheService(StringRedisTemplate redis, CacheMetrics metrics)
    {
        this.redis = redis;
        this.metrics = metrics;
    }

    @Override
    public String get(String key)
    {
        try
        {
            String value = redis.opsForValue().get(key);
            if (value != null)
            {
                metrics.recordHit();
                log.debug("cache hit: {}", key);
                return value;
            }
            metrics.recordMiss();
            log.debug("cache miss: {}", key);
            return null;
        }
        catch (Exception e)
        {
            metrics.recordError();
            log.warn("cache get failed for key {} — falling back to PostgreSQL: {}",
                    key, e.toString());
            return null;
        }
    }

    @Override
    public void put(String key, String jsonValue, Duration ttl)
    {
        try
        {
            redis.opsForValue().set(key, jsonValue, ttl);
            metrics.recordPut();
            log.debug("cache put: {} (ttl {})", key, ttl);
        }
        catch (Exception e)
        {
            metrics.recordError();
            log.warn("cache put failed for key {} — authoritative state stays in PostgreSQL: {}",
                    key, e.toString());
        }
    }

    @Override
    public void evict(String key)
    {
        try
        {
            Boolean deleted = redis.delete(key);
            if (Boolean.TRUE.equals(deleted))
            {
                metrics.recordEviction();
            }
            log.debug("cache evict: {} (deleted={})", key, deleted);
        }
        catch (Exception e)
        {
            metrics.recordError();
            log.warn("cache evict failed for key {} — TTL will bound staleness: {}",
                    key, e.toString());
        }
    }

    @Override
    public void evictByPattern(String pattern)
    {
        try
        {
            Set<String> keys = scanKeys(pattern);
            if (keys != null && !keys.isEmpty())
            {
                Long deleted = redis.delete(keys);
                if (deleted != null && deleted > 0)
                {
                    metrics.recordEviction();
                }
                log.debug("cache evict-by-pattern: {} ({} keys)", pattern, deleted);
            }
        }
        catch (Exception e)
        {
            metrics.recordError();
            log.warn("cache evict-by-pattern failed for {} — TTL will bound staleness: {}",
                    pattern, e.toString());
        }
    }

    /**
     * Finds matching keys with {@code SCAN} (cursor-based, count-hinted) —
     * safe for production key spaces, unlike {@code KEYS}.
     */
    private Set<String> scanKeys(String pattern)
    {
        return redis.execute((RedisCallback<Set<String>>) connection ->
        {
            Set<String> matched = new HashSet<>();
            try (Cursor<byte[]> cursor = connection.scan(
                    ScanOptions.scanOptions().match(pattern).count(SCAN_BATCH_SIZE).build()))
            {
                while (cursor.hasNext())
                {
                    matched.add(new String(cursor.next(), StandardCharsets.UTF_8));
                }
            }
            return matched;
        });
    }

    @Override
    public boolean isEnabled()
    {
        return true;
    }
}