package logic_core.infrastructure.cache;

import logic_core.app.cache.CacheService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Cache bean selection (V2.1 #19).
 *
 * <p>Redis-backed caching is activated by {@code app.cache.enabled=true}
 * (environment-driven). Otherwise a {@link NoopCacheService} is provided so
 * all application code depends on {@link CacheService} regardless of
 * deployment, and PostgreSQL remains the only required runtime dependency.
 *
 * <p><b>Isolation for future testing migrations:</b> everything Redis-specific
 * lives behind {@link CacheService} and connection settings are plain
 * {@code spring.data.redis.*} properties — swapping embedded Redis for
 * Testcontainers later changes only test property sources, not application or
 * test code.
 */
@Configuration
public class CacheConfiguration
{
    @Bean
    public CacheMetrics cacheMetrics()
    {
        return new CacheMetrics();
    }

    @Bean
    @ConditionalOnProperty(name = "app.cache.enabled", havingValue = "true")
    public CacheService redisCacheService(
            StringRedisTemplate stringRedisTemplate,
            CacheMetrics cacheMetrics)
    {
        return new RedisCacheService(stringRedisTemplate, cacheMetrics);
    }

    @Bean
    @ConditionalOnMissingBean(CacheService.class)
    public CacheService noopCacheService()
    {
        return new NoopCacheService();
    }
}