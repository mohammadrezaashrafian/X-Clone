package logic_core.app.service.ratelimit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Rate-limit policy for password-based login (Issue #21 security baseline).
 *
 * <p>Fixed window per username: the default allows {@code 5} login attempts
 * per {@code 15} minutes for a given username. Both values are
 * environment-overridable and clamped to safe bounds so a misconfigured
 * deployment can neither lock out everyone nor disable the protection.
 *
 * <p>This bounds online password guessing per account. It is anti-abuse
 * only: the limiter is fail-open (see {@link EmailRateLimiter}), so it is
 * never a correctness dependency, and it never extends a window on blocked
 * attempts.
 */
@Component
public class LoginRateLimits
{
    private final int loginMax;
    private final Duration loginWindow;

    public LoginRateLimits(
            @Value("${app.rate-limit.login.max:5}") int loginMax,
            @Value("${app.rate-limit.login.window-minutes:15}") long loginWindowMinutes)
    {
        this.loginMax = Math.max(1, Math.min(20, loginMax));
        this.loginWindow = Duration.ofMinutes(
                Math.max(1, Math.min(1440, loginWindowMinutes)));
    }

    public int loginMax()
    {
        return loginMax;
    }

    public Duration loginWindow()
    {
        return loginWindow;
    }
}
