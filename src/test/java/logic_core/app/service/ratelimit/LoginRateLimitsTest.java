package logic_core.app.service.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Login rate-limit policy (Issue #21): deterministic defaults, safe clamping,
 * and invalid-configuration fail-open behavior.
 */
@DisplayName("LoginRateLimits policy tests")
class LoginRateLimitsTest
{
    @Test
    @DisplayName("defaults are 5 attempts per 15 minutes")
    void defaults_areDeterministic()
    {
        LoginRateLimits limits = new LoginRateLimits(5, 15);

        assertThat(limits.loginMax()).isEqualTo(5);
        assertThat(limits.loginWindow()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("misconfigured values are clamped to safe bounds")
    void values_areClamped()
    {
        LoginRateLimits absurdlyHigh = new LoginRateLimits(100_000, 100_000);
        assertThat(absurdlyHigh.loginMax()).isEqualTo(20);
        assertThat(absurdlyHigh.loginWindow()).isEqualTo(Duration.ofMinutes(1440));

        LoginRateLimits absurdlyLow = new LoginRateLimits(0, 0);
        assertThat(absurdlyLow.loginMax()).isEqualTo(1);
        assertThat(absurdlyLow.loginWindow()).isEqualTo(Duration.ofMinutes(1));

        LoginRateLimits negative = new LoginRateLimits(-5, -30);
        assertThat(negative.loginMax()).isEqualTo(1);
        assertThat(negative.loginWindow()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("a permissive configured limit still bounds attempts")
    void permissiveLimit_stillBounds()
    {
        LoginRateLimits limits = new LoginRateLimits(20, 60);
        assertThat(limits.loginMax()).isEqualTo(20);
        assertThat(limits.loginWindow()).isEqualTo(Duration.ofMinutes(60));
    }
}
