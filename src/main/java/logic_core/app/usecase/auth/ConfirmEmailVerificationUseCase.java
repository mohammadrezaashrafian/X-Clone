package logic_core.app.usecase.auth;

import logic_core.app.dto.request.ConfirmEmailVerificationRequest;
import logic_core.app.dto.response.EmailVerificationConfirmResponse;
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
 * Email verification confirmation (EMAIL_VERIFY_CONFIRM, Issue #20).
 *
 * <p>The code is verified against the authenticated user's current email; on
 * success the account's {@code email_verified} state flips to true
 * atomically within this transaction.
 */
@Service
@RequiredArgsConstructor
public class ConfirmEmailVerificationUseCase
{
    @NonNull private final AuthLockOrchestrator lockOrchestrator;
    @NonNull private final PasswordResetOtpService otpService;
    @NonNull private final UserRepository userRepository;

    @Transactional
    public Result<EmailVerificationConfirmResponse> execute(ConfirmEmailVerificationRequest request)
    {
        if (request == null || request.code() == null || request.code().isBlank())
        {
            return Result.failure("Invalid or expired code.");
        }

        try
        {
            SessionUserContext context =
                    lockOrchestrator.lockAndGetContextByToken(request.sessionToken());

            String email = context.lockedUser().getEmail();

            OtpVerifyStatus status = otpService.verify(email, request.code());

            if (status != OtpVerifyStatus.OK)
            {
                return Result.failure(mapFailure(status));
            }

            UserModel user = context.lockedUser();
            user.setEmailVerified(true);

            userRepository.update(user);

            return Result.success(new EmailVerificationConfirmResponse(true, "Email verified."));
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
            case OK -> "Email verification failed.";
            case NOT_FOUND, INVALID_CODE -> "Invalid or expired code.";
            case EXPIRED -> "Code has expired. Please request a new one.";
            case TOO_MANY_ATTEMPTS -> "Too many attempts. Please request a new code.";
        };
    }
}