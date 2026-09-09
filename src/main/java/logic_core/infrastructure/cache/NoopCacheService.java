package logic_core.infrastructure.cache;

import logic_core.app.cache.CacheService;

import java.time.Duration;

/**
 * No-op {@link CacheService} used when caching is disabled
 * ({@code app.cache.enabled=false}, the default in the test classpath and on
 * environments without Redis). Every call is a miss/no-op, leaving all
 * callers byte-for-byte unchanged.
 */
public class NoopCacheService implements CacheService
{
    @Override
    public String get(String key)
    {
        return null;
    }

    @Override
    public void put(String key, String jsonValue, Duration ttl)
    {
        // no-op
    }

    @Override
    public void evict(String key)
    {
        // no-op
    }

    @Override
    public void evictByPattern(String pattern)
    {
        // no-op
    }

    @Override
    public boolean isEnabled()
    {
        return false;
    }
}