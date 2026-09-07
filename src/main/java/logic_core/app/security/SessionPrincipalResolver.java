package logic_core.app.security;

import logic_core.common.security.AuthPrincipal;
import logic_core.domain.model.SessionModel;
import logic_core.domain.model.UserModel;
import logic_core.domain.repository.UserRepository;
import logic_core.session.SessionManager;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves a session token into an {@link AuthPrincipal} for transport-level
 * request authentication.
 *
 * <p>This resolver is intentionally read-only: it validates that a session
 * exists and has not expired ({@link SessionManager#findValidSession}) and
 * loads the owning user, but it performs <b>no row locking</b>. It exists to
 * let the HTTP transport establish a per-request principal before a protected
 * operation executes and to reject missing/invalid credentials up front.
 *
 * <p>The authoritative per-operation validation remains
 * {@link AuthLockOrchestrator#lockAndGetContextByToken}, which every
 * authenticated use case still calls inside its own transaction. That call
 * re-validates the session and performs the {@code SELECT ... FOR UPDATE}
 * row-locking/concurrency semantics through the user repository; it must not be
 * bypassed or removed. The duplication between transport-level principal
 * resolution (read-only, per request) and application-level session resolution
 * (transactional, per operation) is intentional: the first gates and labels the
 * request, the second guards the business operation.
 */
@Component
@RequiredArgsConstructor
public class SessionPrincipalResolver
{
    @NonNull
    private final SessionManager sessionManager;

    @NonNull
    private final UserRepository userRepository;

    /**
     * @param sessionToken the opaque session token presented by the request, or
     *                     {@code null}/{@code blank} when none is present
     * @return the principal for a valid, unexpired session whose owning user
     *         still exists; empty when the token is absent, unknown, revoked,
     *         expired, or the owning user no longer exists
     */
    public Optional<AuthPrincipal> resolve(String sessionToken)
    {
        if (sessionToken == null || sessionToken.isBlank())
        {
            return Optional.empty();
        }

        return sessionManager.findValidSession(sessionToken)
                .flatMap(session -> loadUser(session.getUserId())
                        .map(user -> new AuthPrincipal(
                                user.getId(),
                                user.getUsername(),
                                session.getId(),
                                session.getToken()
                        )));
    }

    private Optional<UserModel> loadUser(UUID userId)
    {
        return userRepository.findById(userId);
    }
}
