package logic_core.infrastructure.repository;

import jakarta.persistence.LockModeType;
import logic_core.infrastructure.persistence.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;
import java.util.List;

@Repository
public interface UserJpaRepository extends JpaRepository<UserEntity, UUID> {
    Optional<UserEntity> findByUsernameAndIsDeletedFalse(String username);
    Optional<UserEntity> findByEmailAndIsDeletedFalse(String email);
    boolean existsByUsernameAndIsDeletedFalse(String username);
    boolean existsByEmailAndIsDeletedFalse(String email);
    Optional<UserEntity> findByIdAndIsDeletedFalse(UUID id);
    boolean existsByIdAndIsDeletedFalse(UUID id);
    boolean existsByIdAndIsActiveTrueAndIsDeletedFalse(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM UserEntity u WHERE u.id = :id AND u.isDeleted = false")
    Optional<UserEntity> findWithLockByIdAndIsDeletedFalse(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM UserEntity u WHERE u.username = :username AND u.isDeleted = false")
    Optional<UserEntity> findWithLockByUsernameAndIsDeletedFalse(@Param("username") String username);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM UserEntity u WHERE u.email = :email AND u.isDeleted = false")
    Optional<UserEntity> findWithLockByEmailAndIsDeletedFalse(@Param("email") String email);

    @Query("SELECT s.user FROM SessionEntity s WHERE s.id = :sessionId AND s.user.isDeleted = false")
    Optional<UserEntity> findUserBySessionId(@Param("sessionId") UUID sessionId);

    @Query("SELECT t.author FROM TweetEntity t WHERE t.id = :tweetId AND t.isDeleted = false AND t.author.isDeleted = false")
    Optional<UserEntity> findAuthorByTweetId(@Param("tweetId") UUID tweetId);

    @Query("SELECT u FROM UserEntity u WHERE u.isDeleted = false AND u.id <> :actorId AND (u.username LIKE %:query% OR u.displayName LIKE %:query%) ORDER BY u.username ASC")
    List<UserEntity> searchUsers(@Param("actorId") UUID actorId,
                                 @Param("query") String query,
                                 org.springframework.data.domain.Pageable pageable);
}
