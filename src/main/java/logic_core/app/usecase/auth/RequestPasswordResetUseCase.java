package logic_core.app.usecase.auth;

import logic_core.app.dto.request.RequestPasswordResetRequest;
import logic_core.app.dto.response.RequestPasswordResetResponse;
import logic_core.app.dto.validator.EmailValidator;
import logic_core.app.service.email.EmailMessage;
import logic_core.app.service.email.EmailMessageType;
import logic_core.app.service.email.EmailNotificationService;
import logic_core.app.service.passwordReset.PasswordResetOtpService;
import logic_core.app.service.ratelimit.EmailRateLimiter;
import logic_core.app.service.ratelimit.EmailRateLimits;
import logic_core.common.exception.ValidationException;
import logic_core.common.result.Result;
import logic_core.domain.model.UserModel;
import logic_core.domain.repository.UserRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

/**
 * Password reset request (AUTH_REQUEST_PASSWORD_RESET, Issue #20).
 *
 * <p><b>Delivery ordering</b> — the OTP is created inside this transaction,
 * and the email is scheduled via {@link EmailNotificationService} so it is
 * dispatched only after the transaction commits (a rolled-back transaction
 * never sends an email). The OTP remains valid even if the provider later
 * fails, per the documented delivery-failure policy.
 *
 * <p><b>Anti-abuse</b> — rate limited per email address; when the limit is
 * exceeded the use case issues nothing and still returns the same generic
 * success, so enumeration is impossible and inboxes are not flooded.
 */
@Service
@RequiredArgsConstructor
public class RequestPasswordResetUseCase
{
    private static final String GENERIC_MESSAGE =
            "If an account exists for this email, a reset code has been sent.";

    private static final String RATE_LIMIT_OPERATION = "password_reset";

    @NonNull private final UserRepository userRepository;
    @NonNull private final PasswordResetOtpService otpService;
    @NonNull private final EmailNotificationService emailService;
    @NonNull private final EmailRateLimiter rateLimiter;
    @NonNull private final EmailRateLimits rateLimits;
    @NonNull private final EmailValidator emailValidator;

    @Transactional
    public Result<RequestPasswordResetResponse> execute(RequestPasswordResetRequest request)
    {
        if (request == null)
        {
            return Result.failure("Invalid request.");
        }

        final String email = request.email().trim();

        try
        {
            emailValidator.validate(email);
        }
        catch (ValidationException | IllegalArgumentException e)
        {
            return Result.failure(e.getMessage());
        }

        // Anti-enumeration: same success path whether user exists or not.
        Optional<UserModel> userOpt = userRepository.findByEmail(email);

        if (userOpt.isPresent() && rateLimiter.tryAcquire(
                RATE_LIMIT_OPERATION,
                email,
                rateLimits.passwordResetMax(),
                rateLimits.passwordResetWindow()))
        {
            UserModel user = userOpt.get();

            String rawOtp = otpService.issue(email, user.getId());

            // Rendered and delivered (async) only after commit; raw code is
            // never logged by any layer.
            emailService.sendAfterCommit(new EmailMessage(
                    email,
                    EmailMessageType.PASSWORD_RESET,
                    Map.of(
                            "code", rawOtp,
                            "email", email,
                            "appName", "X-Clone",
                            "expiresInMinutes", String.valueOf(
                                    PasswordResetOtpService.DEFAULT_TTL.toMinutes())
                    )
            ));
        }

        return Result.success(new RequestPasswordResetResponse(GENERIC_MESSAGE));
    }
}