package logic_core.app.usecase.auth;

import logic_core.app.dto.request.LoginRequest;
import logic_core.app.dto.response.AuthResponse;
import logic_core.app.dto.validator.LoginValidator;
import logic_core.app.mapper.AuthMapper;
import logic_core.app.security.AuthLockOrchestrator;
import logic_core.app.security.SessionUserContext;
import logic_core.app.service.ratelimit.EmailRateLimiter;
import logic_core.app.service.ratelimit.LoginRateLimits;
import logic_core.common.exception.NotFoundException;
import logic_core.common.result.Result;
import logic_core.common.security.PasswordHasher;
import logic_core.common.util.TimeProvider;
import logic_core.domain.model.SessionModel;
import logic_core.domain.model.UserModel;
import logic_core.session.SessionManager;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoginUserUseCase
{
    private static final String RATE_LIMIT_OPERATION = "login";

    /** Never reveals whether the username exists (anti-enumeration). */
    private static final String GENERIC_FAILURE = "Invalid credentials.";

    @NonNull private final LoginValidator validator;
    @NonNull private final PasswordHasher passwordHasher;
    @NonNull private final SessionManager sessionManager;
    @NonNull private final AuthLockOrchestrator lockOrchestrator;
    @NonNull private final EmailRateLimiter rateLimiter;
    @NonNull private final LoginRateLimits rateLimits;

    @Transactional
    public Result<AuthResponse> execute(LoginRequest request)
    {
        try
        {
            validator.validate(request.username(), request.password());
        }
        catch (IllegalArgumentException e)
        {
            return Result.failure(e.getMessage());
        }

        // Per-username attempt bound (online password guessing). The limiter
        // is fail-open (Issue #20 contract) and never a correctness
        // dependency; blocked attempts do not extend the fixed window. The
        // username is normalized (trim + lowercase) so the limit cannot be
        // bypassed by case/whitespace variants.
        if (!rateLimiter.tryAcquire(
                RATE_LIMIT_OPERATION,
                request.username().trim().toLowerCase(Locale.ROOT),
                rateLimits.loginMax(),
                rateLimits.loginWindow()))
        {
            log.warn("login rate limit exceeded for username=[masked]");
            return Result.failure(GENERIC_FAILURE);
        }

        SessionUserContext context;
        try
        {
            context = lockOrchestrator.lockAndGetUserByUsername(request.username());
        }
        catch (NotFoundException e)
        {
            // Anti-enumeration: an unknown username is indistinguishable
            // from a wrong password. (Issue #21)
            return Result.failure(GENERIC_FAILURE);
        }

        UserModel lockedUser = context.lockedUser();

        boolean passwordMatches = passwordHasher.verify(
                request.password(),
                lockedUser.getPasswordHash()
        );

        if (!passwordMatches)
        {
            return Result.failure(GENERIC_FAILURE);
        }

        SessionModel session = sessionManager.startSession(lockedUser.getId());


        return Result.success(AuthMapper.toResponse(lockedUser, session));
    }
}
