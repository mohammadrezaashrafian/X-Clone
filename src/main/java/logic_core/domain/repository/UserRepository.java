package logic_core.domain.repository;

import logic_core.domain.model.UserModel;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository
{
    Optional<UserModel> save(UserModel user);

    void update(UserModel user);

    void delete(UserModel user);

    Optional<UserModel> findById(UUID userId);

    /**
     * Loads multiple users in one round trip. Missing ids are simply absent
     * from the result. Used to batch-resolve notification actors (V2.1 #7).
     */
    List<UserModel> findByIds(List<UUID> userIds);

    Optional<UserModel> findByIdForUpdate(UUID userId);

    Optional<UserModel> findByUsername(String username);

    Optional<UserModel> findByEmail(String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    boolean existsById(UUID userId);

    Optional<UserModel> findUserBySessionId(UUID sessionId);

    boolean isActive(UserModel user);

//    Optional<UserModel> findAuthorByMediaId(UUID mediaId);

Optional<UserModel> findByUsernameForUpdate(String username);

    /**
     * Loads the non-deleted user with the given email using a pessimistic
     * write lock, mirroring {@link #findByUsernameForUpdate(String)} for
     * login-by-email.
     */
    Optional<UserModel> findByEmailForUpdate(String email);

    List<UserModel> searchUsers(UUID actorId, String query, int limit, int pageSize);
}
