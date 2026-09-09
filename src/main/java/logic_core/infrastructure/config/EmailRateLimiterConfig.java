package logic_core.infrastructure.config;

import logic_core.app.service.ratelimit.EmailRateLimiter;
import logic_core.app.service.ratelimit.InMemoryEmailRateLimiter;
import logic_core.infrastructure.ratelimit.RedisEmailRateLimiter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Rate-limiter selection (Issue #20).
 *
 * <p>In-memory limiter is the default (works everywhere, no infrastructure).
 * When Redis is enabled (Issue #19 {@code app.cache.enabled=true}), the
 * Redis-backed fail-open limiter replaces it. Redis is never required for
 * correctness: the Redis limiter returns allow on any failure.
 */
@Configuration
public class EmailRateLimiterConfig
{
    @Bean
    @ConditionalOnProperty(name = "app.cache.enabled", havingValue = "true")
    public EmailRateLimiter redisEmailRateLimiter(StringRedisTemplate stringRedisTemplate)
    {
        return new RedisEmailRateLimiter(stringRedisTemplate);
    }

    @Bean
    @ConditionalOnMissingBean(EmailRateLimiter.class)
    public EmailRateLimiter inMemoryEmailRateLimiter()
    {
        return new InMemoryEmailRateLimiter();
    }
}