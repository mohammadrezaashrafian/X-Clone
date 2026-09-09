package logic_core.infrastructure.ratelimit;

import logic_core.app.service.ratelimit.EmailRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * Optional Redis-backed {@link EmailRateLimiter} (Issue #20), selected when
 * {@code app.cache.enabled=true} (reuses Issue #19's Redis infrastructure).
 *
 * <p>Atomic fixed-window counter via {@code INCR} + {@code EXPIRE}, keyed
 * {@code xc:ratelimit:{operation}:{subject}} so email limits share the
 * namespaced Redis key space and never collide with cached resources.
 *
 * <p><b>Fail-open by design:</b> rate limiting is anti-abuse, never a
 * correctness dependency. If Redis is unreachable, {@link #tryAcquire}
 * returns {@code true} (allow) and logs a warning — authentication/email
 * flows must never crash merely because the optional limiter cannot reach
 * Redis.
 */
public class RedisEmailRateLimiter implements EmailRateLimiter
{
    private static final Logger log = LoggerFactory.getLogger(RedisEmailRateLimiter.class);

    private static final String KEY_PREFIX = "xc:ratelimit:";

    private final StringRedisTemplate redis;

    public RedisEmailRateLimiter(StringRedisTemplate redis)
    {
        this.redis = redis;
    }

    @Override
    public boolean tryAcquire(
            String operation,
            String subject,
            int maxRequests,
            Duration window)
    {
        if (maxRequests <= 0 || window == null || window.isZero() || window.isNegative())
        {
            return true;
        }

        try
        {
            String key = KEY_PREFIX + operation + ":" + subject;
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L)
            {
                redis.expire(key, window);
            }
            return count == null || count <= maxRequests;
        }
        catch (Exception e)
        {
            log.warn("redis rate limiter unavailable — allowing request (fail-open): {}",
                    e.toString());
            return true;
        }
    }
}