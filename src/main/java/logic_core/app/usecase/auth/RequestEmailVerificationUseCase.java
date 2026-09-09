package logic_core.app.usecase.auth;

import logic_core.app.dto.request.RequestEmailVerificationRequest;
import logic_core.app.dto.response.EmailVerificationRequestResponse;
import logic_core.app.security.AuthLockOrchestrator;
import logic_core.app.security.SessionUserContext;
import logic_core.app.service.email.EmailMessage;
import logic_core.app.service.email.EmailMessageType;
import logic_core.app.service.email.EmailNotificationService;
import logic_core.app.service.passwordReset.PasswordResetOtpService;
import logic_core.app.service.ratelimit.EmailRateLimiter;
import logic_core.app.service.ratelimit.EmailRateLimits;
import logic_core.common.exception.AppException;
import logic_core.common.result.Result;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Self-service email verification request (EMAIL_VERIFY_REQUEST, Issue #20).
 *
 * <p>The actor is the authenticated session user; a code is issued for the
 * account's current email and delivered after commit. Registration/login are
 * deliberately not gated on verification — this flow only marks the account
 * verified once the user proves ownership.
 */
@Service
@RequiredArgsConstructor
public class RequestEmailVerificationUseCase
{
    private static final String GENERIC_MESSAGE =
            "If your email is available, a verification code has been sent.";

    private static final String RATE_LIMIT_OPERATION = "email_verification";

    @NonNull private final AuthLockOrchestrator lockOrchestrator;
    @NonNull private final PasswordResetOtpService otpService;
    @NonNull private final EmailNotificationService emailService;
    @NonNull private final EmailRateLimiter rateLimiter;
    @NonNull private final EmailRateLimits rateLimits;

    @Transactional
    public Result<EmailVerificationRequestResponse> execute(RequestEmailVerificationRequest request)
    {
        if (request == null)
        {
            return Result.failure("Invalid request.");
        }

        try
        {
            SessionUserContext context =
                    lockOrchestrator.lockAndGetContextByToken(request.sessionToken());

            String email = context.lockedUser().getEmail();

            if (rateLimiter.tryAcquire(
                    RATE_LIMIT_OPERATION,
                    context.lockedUser().getId().toString(),
                    rateLimits.emailVerificationMax(),
                    rateLimits.emailVerificationWindow()))
            {
                String rawOtp = otpService.issue(email, context.lockedUser().getId());

                emailService.sendAfterCommit(new EmailMessage(
                        email,
                        EmailMessageType.EMAIL_VERIFICATION,
                        Map.of(
                                "code", rawOtp,
                                "email", email,
                                "appName", "X-Clone",
                                "expiresInMinutes", String.valueOf(
                                        PasswordResetOtpService.DEFAULT_TTL.toMinutes())
                        )
                ));
            }

            return Result.success(new EmailVerificationRequestResponse(GENERIC_MESSAGE));
        }
        catch (AppException e)
        {
            return Result.failure(e.getMessage());
        }
    }
}