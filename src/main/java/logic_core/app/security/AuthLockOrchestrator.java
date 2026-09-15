package logic_core.app.security;

import logic_core.common.exception.UnauthorizedException;
import logic_core.domain.model.UserModel;
import logic_core.domain.repository.UserRepository;
import logic_core.session.SessionManager;
import logic_core.domain.model.SessionModel;
import logic_core.common.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AuthLockOrchestrator
{
    @NonNull private final UserRepository userRepository;
    @NonNull private final SessionManager sessionManager;

    @Transactional
    public SessionUserContext lockAndGetContextByToken(String sessionToken)
    {
        SessionModel session = sessionManager.findValidSession(sessionToken)
                .orElseThrow(() -> new NotFoundException("Session not found or invalid."));

        UUID userId = session.getUserId();

        UserModel lockedUser = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new NotFoundException("User not found during locking."));

        if (!lockedUser.getId().equals(session.getUserId()))
        {
            throw new UnauthorizedException("Session does not belong to locked user.");
        }

        return new SessionUserContext(lockedUser, session);
    }

    @Transactional
    public SessionUserContext lockAndGetContextByUserId(UUID userId)
    {
        UserModel lockedUser = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new NotFoundException("User not found during locking."));

        return new SessionUserContext(lockedUser, null);
    }

    /**
     * Locks the user row identified by {@code username}.
     *
     * <p>{@code noRollbackFor = NotFoundException.class} (Issue #21): this method
     * performs only a {@code SELECT ... FOR UPDATE} and writes nothing, so a
     * missing user must not mark the caller's shared transaction rollback-only.
     * {@link logic_core.app.usecase.auth.LoginUserUseCase} catches the
     * {@code NotFoundException} to return its anti-enumeration generic failure;
     * without this rule the outer login transaction would fail to commit with a
     * spurious {@code UnexpectedRollbackException} (surfacing as HTTP 500) after
     * the exception had been handled. Only this lookup is exempted — the
     * token-based context methods keep strict rollback semantics.
     */
    @Transactional(noRollbackFor = NotFoundException.class)
    public SessionUserContext lockAndGetUserByUsername(String username)
    {
        UserModel lockedUser = userRepository.findByUsernameForUpdate(username)
                .orElseThrow(() -> new NotFoundException("User not found during locking."));


        return new SessionUserContext(lockedUser, null);
    }

    /**
     * Locks the user row identified by {@code email} for login-by-email.
     * Same no-rollback rule as {@link #lockAndGetUserByUsername(String)}: the
     * lookup writes nothing, so a missing user must not poison the caller's
     * shared transaction (Issue #21 anti-enumeration handling).
     */
    @Transactional(noRollbackFor = NotFoundException.class)
    public SessionUserContext lockAndGetUserByEmail(String email)
    {
        UserModel lockedUser = userRepository.findByEmailForUpdate(email)
                .orElseThrow(() -> new NotFoundException("User not found during locking."));

        return new SessionUserContext(lockedUser, null);
    }
}
