package logic_core.app.usecase.auth;

import logic_core.app.cache.CacheInvalidation;
import logic_core.app.cache.CacheKeys;
import logic_core.app.dto.request.ConfirmEmailChangeRequest;
import logic_core.app.dto.response.EmailChangeConfirmResponse;
import logic_core.app.security.AuthLockOrchestrator;
import logic_core.app.security.SessionUserContext;
import logic_core.app.service.passwordReset.OtpVerifyStatus;
import logic_core.app.service.passwordReset.PasswordResetOtpService;
import logic_core.common.exception.AppException;
import logic_core.common.result.Result;
import logic_core.domain.model.UserModel;
import logic_core.domain.repository.UserRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Email-change confirmation (EMAIL_CHANGE_CONFIRM, Issue #20).
 *
 * <p>Applies a pending email change (set by {@code USER_UPDATE_EMAIL}) only
 * after the code sent to the <b>new</b> address is verified. Until this
 * transaction commits, the old email remains the account's authoritative
 * address — a fresh uniqueness check on the pending address protects against
 * a race where it was claimed by someone else in the meantime.
 */
@Service
@RequiredArgsConstructor
public class ConfirmEmailChangeUseCase
{
    @NonNull private final AuthLockOrchestrator lockOrchestrator;
    @NonNull private final PasswordResetOtpService otpService;
    @NonNull private final UserRepository userRepository;
    @NonNull private final CacheInvalidation cacheInvalidation;

    @Transactional
    public Result<EmailChangeConfirmResponse> execute(ConfirmEmailChangeRequest request)
    {
        if (request == null || request.code() == null || request.code().isBlank())
        {
            return Result.failure("Invalid or expired code.");
        }

        try
        {
            SessionUserContext context =
                    lockOrchestrator.lockAndGetContextByToken(request.sessionToken());

            UserModel user = context.lockedUser();

            String pendingEmail = user.getPendingEmail();
            if (pendingEmail == null || pendingEmail.isBlank())
            {
                return Result.failure("No pending email change found.");
            }

            OtpVerifyStatus status = otpService.verify(pendingEmail, request.code());

            if (status != OtpVerifyStatus.OK)
            {
                return Result.failure(mapFailure(status));
            }

            // Re-check uniqueness at finalize time: the address may have been
            // claimed by another active account after USER_UPDATE_EMAIL.
            if (userRepository.existsByEmail(pendingEmail))
            {
                user.setPendingEmail(null);
                userRepository.update(user);
                return Result.failure("This email is already in use.");
            }

            user.setEmail(pendingEmail);
            user.setPendingEmail(null);
            user.setEmailVerified(true);

            userRepository.update(user);

            cacheInvalidation.evictAfterCommit(CacheKeys.userProfile(user.getId()));

            return Result.success(new EmailChangeConfirmResponse(
                    true,
                    "Email address updated.",
                    user.getEmail()
            ));
        }
        catch (AppException e)
        {
            return Result.failure(e.getMessage());
        }
    }

    private static String mapFailure(OtpVerifyStatus status)
    {
        return switch (status)
        {
            case OK -> "Email change failed.";
            case NOT_FOUND, INVALID_CODE -> "Invalid or expired code.";
            case EXPIRED -> "Code has expired. Please request a new one.";
            case TOO_MANY_ATTEMPTS -> "Too many attempts. Please request a new code.";
        };
    }
}