package logic_core.app.service.ratelimit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Rate-limit policy for email-triggering operations (Issue #20).
 *
 * <p>Limits are fixed windows per operation + subject:
 *
 * <ul>
 *   <li><b>Password reset</b> — 3 requests / 15 minutes per email address.</li>
 *   <li><b>Email verification request</b> — 3 / 10 minutes per authenticated user.</li>
 *   <li><b>Email change request</b> — 3 / 15 minutes per authenticated user.</li>
 * </ul>
 *
 * <p>All values are environment-overridable and never exceed a hard cap so a
 * misconfigured deployment cannot shoot itself in the foot.
 */
@Component
public class EmailRateLimits
{
    private final int passwordResetMax;
    private final Duration passwordResetWindow;

    private final int emailVerificationMax;
    private final Duration emailVerificationWindow;

    private final int emailChangeMax;
    private final Duration emailChangeWindow;

    public EmailRateLimits(
            @Value("${app.email.rate-limit.password-reset.max:3}") int passwordResetMax,
            @Value("${app.email.rate-limit.password-reset.window-minutes:15}") long passwordResetWindowMinutes,
            @Value("${app.email.rate-limit.verification.max:3}") int emailVerificationMax,
            @Value("${app.email.rate-limit.verification.window-minutes:10}") long emailVerificationWindowMinutes,
            @Value("${app.email.rate-limit.email-change.max:3}") int emailChangeMax,
            @Value("${app.email.rate-limit.email-change.window-minutes:15}") long emailChangeWindowMinutes)
    {
        this.passwordResetMax = clamp(passwordResetMax, 1, 10);
        this.passwordResetWindow = Duration.ofMinutes(clampMinutes(passwordResetWindowMinutes));

        this.emailVerificationMax = clamp(emailVerificationMax, 1, 10);
        this.emailVerificationWindow = Duration.ofMinutes(clampMinutes(emailVerificationWindowMinutes));

        this.emailChangeMax = clamp(emailChangeMax, 1, 10);
        this.emailChangeWindow = Duration.ofMinutes(clampMinutes(emailChangeWindowMinutes));
    }

    public int passwordResetMax()
    {
        return passwordResetMax;
    }

    public Duration passwordResetWindow()
    {
        return passwordResetWindow;
    }

    public int emailVerificationMax()
    {
        return emailVerificationMax;
    }

    public Duration emailVerificationWindow()
    {
        return emailVerificationWindow;
    }

    public int emailChangeMax()
    {
        return emailChangeMax;
    }

    public Duration emailChangeWindow()
    {
        return emailChangeWindow;
    }

    private static int clamp(int value, int min, int max)
    {
        return Math.max(min, Math.min(max, value));
    }

    private static long clampMinutes(long minutes)
    {
        return Math.max(1, Math.min(1440, minutes));
    }
}