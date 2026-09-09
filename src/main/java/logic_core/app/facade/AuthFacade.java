package logic_core.app.facade;

import logic_core.app.dto.request.*;
import logic_core.app.dto.response.*;
import logic_core.app.usecase.auth.*;
import logic_core.common.result.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthFacade
{
    private final RegisterUserUseCase registerUserUseCase;
    private final LoginUserUseCase loginUserUseCase;
    private final LogoutUserUseCase logoutUserUseCase;
    private final RefreshSessionUseCase refreshSessionUseCase;
    private final RequestPasswordResetUseCase requestPasswordResetUseCase;
    private final VerifyPasswordResetCodeUseCase verifyPasswordResetCodeUseCase;
    private final ResetPasswordUseCase resetPasswordUseCase;
    private final RequestEmailVerificationUseCase requestEmailVerificationUseCase;
    private final ConfirmEmailVerificationUseCase confirmEmailVerificationUseCase;
    private final ConfirmEmailChangeUseCase confirmEmailChangeUseCase;

    public Result<AuthResponse> register(RegisterRequest request)
    {
        return registerUserUseCase.execute(request);
    }

    public Result<AuthResponse> login(LoginRequest request)
    {
        return loginUserUseCase.execute(request);
    }

    public Result<LogoutResponse> logout(LogoutRequest request)
    {
        return logoutUserUseCase.execute(request);
    }

    public Result<AuthResponse> refresh(RefreshSessionRequest request)
    {
        return refreshSessionUseCase.execute(request);
    }

    public Result<RequestPasswordResetResponse> requestPasswordReset(
            RequestPasswordResetRequest request
    )
    {
        return requestPasswordResetUseCase.execute(request);
    }

    public Result<VerifyPasswordResetCodeResponse> verifyPasswordResetCode(
            VerifyPasswordResetCodeRequest request
    )
    {
        return verifyPasswordResetCodeUseCase.execute(request);
    }

    public Result<ResetPasswordResponse> resetPassword(
            ResetPasswordRequest request
    )
    {
        return resetPasswordUseCase.execute(request);
    }

    public Result<EmailVerificationRequestResponse> requestEmailVerification(
            RequestEmailVerificationRequest request
    )
    {
        return requestEmailVerificationUseCase.execute(request);
    }

    public Result<EmailVerificationConfirmResponse> confirmEmailVerification(
            ConfirmEmailVerificationRequest request
    )
    {
        return confirmEmailVerificationUseCase.execute(request);
    }

    public Result<EmailChangeConfirmResponse> confirmEmailChange(
            ConfirmEmailChangeRequest request
    )
    {
        return confirmEmailChangeUseCase.execute(request);
    }
}