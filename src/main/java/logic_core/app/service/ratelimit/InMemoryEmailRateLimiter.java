package logic_core.app.service.ratelimit;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default in-memory {@link EmailRateLimiter} (Issue #20).
 * Registered as a bean by {@code EmailRateLimiterConfig}; not a component so
 * the Redis-backed variant can replace it without ambiguity.
 *
 * <p>Fixed-window counter per (operation, subject), stored in a
 * {@link ConcurrentHashMap} with an expiry timestamp. Entries are lazily
 * evicted when a new request re-encounters an expired window, so no explicit
 * cleanup thread is needed. Consistent with the existing in-memory
 * {@code PasswordResetOtpService} design: state does not survive a process
 * restart, which is acceptable for an anti-abuse window.
 *
 * <p>Thread-safe and never throws on normal operation. Blocked requests are
 * not counted (the counter stays at the limit), so repeated attempts do not
 * extend the window.
 */
public final class InMemoryEmailRateLimiter implements EmailRateLimiter
{
    private record Window(int count, long windowEndEpochMillis)
    {
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    public boolean tryAcquire(
            String operation,
            String subject,
            int maxRequests,
            Duration window)
    {
        if (operation == null || subject == null || window == null || window.isZero() || window.isNegative()
                || maxRequests <= 0)
        {
            // Defensive: never block legitimate traffic on invalid configuration.
            return true;
        }

        String key = operation + ":" + subject;
        long nowMillis = Instant.now().toEpochMilli();
        long windowEnd = nowMillis + window.toMillis();

        Window[] result = {new Window(1, windowEnd)};
        boolean[] allowed = {true};

        windows.compute(key, (k, current) ->
        {
            if (current == null || current.windowEndEpochMillis() <= nowMillis)
            {
                // Fresh window: this request is the first.
                result[0] = new Window(1, windowEnd);
                allowed[0] = true;
                return result[0];
            }

            if (current.count() >= maxRequests)
            {
                // Blocked: keep the counter at the limit so repeated attempts
                // do not extend the window.
                result[0] = current;
                allowed[0] = false;
                return current;
            }

            result[0] = new Window(current.count() + 1, current.windowEndEpochMillis());
            allowed[0] = true;
            return result[0];
        });

        return allowed[0];
    }

    /** Visible for tests. */
    int currentCount(String operation, String subject)
    {
        Window w = windows.get(operation + ":" + subject);
        return w == null ? 0 : w.count();
    }
}