package logic_core.app.usecase.User;

import jakarta.transaction.Transactional;
import logic_core.app.cache.CacheInvalidation;
import logic_core.app.cache.CacheKeys;
import logic_core.app.dto.request.UpdateEmailRequest;
import logic_core.app.dto.validator.EmailValidator;
import logic_core.app.security.AuthLockOrchestrator;
import logic_core.app.security.SessionUserContext;
import logic_core.app.service.email.EmailMessage;
import logic_core.app.service.email.EmailMessageType;
import logic_core.app.service.email.EmailNotificationService;
import logic_core.app.service.passwordReset.PasswordResetOtpService;
import logic_core.app.service.ratelimit.EmailRateLimiter;
import logic_core.app.service.ratelimit.EmailRateLimits;
import logic_core.common.exception.NotFoundException;
import logic_core.common.result.Result;
import logic_core.domain.model.UserModel;
import logic_core.domain.repository.UserRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Email change request (USER_UPDATE_EMAIL, Issue #20).
 *
 * <p><b>Security semantics:</b> the new address is <b>not</b> made
 * authoritative here. It is validated for format and uniqueness, stored as
 * {@code pending_email}, and a confirmation code is sent to the <b>new</b>
 * address. The account's old email keeps working until
 * {@code EMAIL_CHANGE_CONFIRM} verifies ownership of the new one. A rate
 * limit per user bounds how often a code can be requested for the new
 * address.
 */
@Service
@RequiredArgsConstructor
public class UpdateEmailUseCase
{
    private static final String RATE_LIMIT_OPERATION = "email_change";

    @NonNull private final UserRepository repository;
    @NonNull private final AuthLockOrchestrator lockOrchestrator;
    @NonNull private final EmailValidator emailValidator;
    @NonNull private final CacheInvalidation cacheInvalidation;
    @NonNull private final EmailNotificationService emailService;
    @NonNull private final EmailRateLimiter rateLimiter;
    @NonNull private final EmailRateLimits rateLimits;
    @NonNull private final PasswordResetOtpService otpService;

    @Transactional
    public Result<Void> execute(UpdateEmailRequest request)
    {
        try
        {
            SessionUserContext context = lockOrchestrator.lockAndGetContextByToken(request.sessionToken());

            if(!context.lockedUser().getId().equals(request.userId()))
            {
                return Result.failure("cannot update another user's email");
            }

            emailValidator.validate(request.email());


            UserModel user = repository.findById(request.userId())
                    .orElseThrow(() -> new NotFoundException("user not found"));

            String newEmail = request.email().trim().toLowerCase(java.util.Locale.ROOT);

            if(newEmail.equalsIgnoreCase(user.getEmail()))
            {
                return Result.failure("new email is same as current email");
            }

            if(repository.existsByEmail(newEmail))
            {
                return Result.failure("email already exists");
            }

            user.setPendingEmail(newEmail);
            user.setUpdatedAt(OffsetDateTime.now());

            repository.update(user);

            cacheInvalidation.evictAfterCommit(CacheKeys.userProfile(user.getId()));

            if (rateLimiter.tryAcquire(
                    RATE_LIMIT_OPERATION,
                    user.getId().toString(),
                    rateLimits.emailChangeMax(),
                    rateLimits.emailChangeWindow()))
            {
                String rawOtp = otpService.issue(newEmail, user.getId());

                emailService.sendAfterCommit(new EmailMessage(
                        newEmail,
                        EmailMessageType.EMAIL_CHANGE,
                        Map.of(
                                "code", rawOtp,
                                "newEmail", newEmail,
                                "appName", "X-Clone",
                                "expiresInMinutes", String.valueOf(
                                        PasswordResetOtpService.DEFAULT_TTL.toMinutes())
                        )
                ));
            }

            return Result.success(null);
        }
        catch(Exception e)
        {
            return Result.failure(e.getMessage());
        }
    }
}