package logic_core.app.service.ratelimit;

import java.time.Duration;

/**
 * Rate limiting for externally triggerable email operations (Issue #20).
 *
 * <p>Key scope is per-operation + subject (e.g. per email for password
 * reset, per user for verification resend). The limit is a fixed window of
 * {@code maxRequests} within {@code window}.
 *
 * <p>Implementations are <b>fail-open</b>: if the backing store (e.g. Redis)
 * is unavailable, the limiter must allow the request rather than crash the
 * authentication/email flow — rate limiting is an anti-abuse optimization,
 * never a correctness dependency.
 */
public interface EmailRateLimiter
{
    /**
     * Returns true when the subject is still allowed within the window, and
     * records the request; returns false when the limit is exceeded.
     * Never throws on infrastructure failure (see class javadoc).
     */
    boolean tryAcquire(String operation, String subject, int maxRequests, Duration window);
}