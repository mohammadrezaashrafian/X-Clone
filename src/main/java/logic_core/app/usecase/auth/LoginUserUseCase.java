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

        // The identifier is trimmed+lowercased for the rate-limit key so the
        // limit cannot be bypassed by case/whitespace variants. The limiter is
        // fail-open (Issue #20 contract) and never a correctness dependency;
        // blocked attempts do not extend the fixed window.
        String identifier = request.username().trim();
        if (!rateLimiter.tryAcquire(
                RATE_LIMIT_OPERATION,
                identifier.toLowerCase(Locale.ROOT),
                rateLimits.loginMax(),
                rateLimits.loginWindow()))
        {
            log.warn("login rate limit exceeded for identifier=[masked]");
            return Result.failure(GENERIC_FAILURE);
        }

        // The login field accepts "Username or email": inputs containing '@'
        // are treated as email addresses. Both lookups use the same
        // pessimistic-lock/no-rollback handling; an unknown identifier is
        // indistinguishable from a wrong password (Issue #21).
        SessionUserContext context;
        try
        {
            if (identifier.contains("@"))
            {
                context = lockOrchestrator.lockAndGetUserByEmail(identifier);
            }
            else
            {
                context = lockOrchestrator.lockAndGetUserByUsername(identifier);
            }
        }
        catch (NotFoundException e)
        {
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
