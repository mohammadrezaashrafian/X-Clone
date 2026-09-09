package logic_core.app.service.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * In-memory rate limiter (Issue #20): fixed-window semantics, per-subject
 * isolation, window expiry, and invalid-input safety.
 */
class InMemoryEmailRateLimiterTest
{
    private final InMemoryEmailRateLimiter limiter = new InMemoryEmailRateLimiter();

    @Test
    void allowsUpToLimit_thenBlocks()
    {
        for (int i = 0; i < 3; i++)
        {
            assertThat(limiter.tryAcquire("password_reset", "a@example.com", 3, Duration.ofMinutes(15)))
                    .as("request %d allowed", i + 1)
                    .isTrue();
        }

        assertThat(limiter.tryAcquire("password_reset", "a@example.com", 3, Duration.ofMinutes(15)))
                .as("4th request blocked")
                .isFalse();
        assertThat(limiter.tryAcquire("password_reset", "a@example.com", 3, Duration.ofMinutes(15)))
                .as("still blocked (no window extension)")
                .isFalse();

        // Counter stays at the limit; blocked attempts do not extend the window.
        assertThat(limiter.currentCount("password_reset", "a@example.com")).isEqualTo(3);
    }

    @Test
    void subjectsAreIsolatedPerOperation()
    {
        assertThat(limiter.tryAcquire("password_reset", "a@example.com", 3, Duration.ofMinutes(15))).isTrue();
        assertThat(limiter.tryAcquire("password_reset", "a@example.com", 3, Duration.ofMinutes(15))).isTrue();
        assertThat(limiter.tryAcquire("password_reset", "a@example.com", 3, Duration.ofMinutes(15))).isTrue();
        assertThat(limiter.tryAcquire("password_reset", "a@example.com", 3, Duration.ofMinutes(15))).isFalse();

        assertThat(limiter.tryAcquire("email_verification", "a@example.com", 3, Duration.ofMinutes(10)))
                .as("different operation, same subject: independent window")
                .isTrue();

        assertThat(limiter.tryAcquire("password_reset", "b@example.com", 3, Duration.ofMinutes(15)))
                .as("same operation, different subject: independent window")
                .isTrue();
    }

    @Test
    void expiredWindowAllowsAgain()
    {
        assertThat(limiter.tryAcquire("password_reset", "a@example.com", 1, Duration.ofMillis(30))).isTrue();
        assertThat(limiter.tryAcquire("password_reset", "a@example.com", 1, Duration.ofMillis(30))).isFalse();

        try
        {
            Thread.sleep(60);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }

        assertThat(limiter.tryAcquire("password_reset", "a@example.com", 1, Duration.ofMillis(30)))
                .as("window expired -> fresh window allows again")
                .isTrue();
    }

    @Test
    void invalidConfigurationNeverBlocks()
    {
        assertThat(limiter.tryAcquire(null, "s", 3, Duration.ofMinutes(1))).isTrue();
        assertThat(limiter.tryAcquire("op", null, 3, Duration.ofMinutes(1))).isTrue();
        assertThat(limiter.tryAcquire("op", "s", 0, Duration.ofMinutes(1))).isTrue();
        assertThat(limiter.tryAcquire("op", "s", 3, Duration.ZERO)).isTrue();
        assertThat(limiter.tryAcquire("op", "s", 3, Duration.ofMinutes(-1))).isTrue();
    }
}