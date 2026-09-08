package logic_core.infrastructure.repository;

import logic_core.infrastructure.persistence.entity.notification.NotificationEntity;
import logic_core.domain.model.notification.NotificationType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface NotificationJpaRepository
        extends JpaRepository<NotificationEntity, UUID>
{
    @Query("""
        SELECT n FROM NotificationEntity n
        WHERE n.recipient.id = :recipientId
        ORDER BY n.createdAt DESC
        """)
    List<NotificationEntity> findByRecipientOrderByCreatedAtDesc(
            @Param("recipientId") UUID recipientId);

    /**
     * One page of the recipient's notifications, newest first. Backs the
     * paginated NOTIFICATION_GET read model (V2.1 #7).
     */
    @Query("""
        SELECT n FROM NotificationEntity n
        WHERE n.recipient.id = :recipientId
        ORDER BY n.createdAt DESC
        """)
    List<NotificationEntity> findPageByRecipient(
            @Param("recipientId") UUID recipientId,
            Pageable pageable);

    long countByRecipientId(@Param("recipientId") UUID recipientId);

    long countByRecipientIdAndIsReadFalse(@Param("recipientId") UUID recipientId);

    /**
     * Hard-deletes the notification generated for one interaction, so undoing
     * the interaction (unlike / unfollow / unretweet) removes the
     * corresponding notification instead of leaving a ghost. A null tweetId
     * targets tweet-less types (FOLLOW).
     */
    @Modifying
    @Query("""
        DELETE FROM NotificationEntity n
        WHERE n.recipient.id = :recipientId
          AND n.actor.id = :actorId
          AND n.type = :type
          AND ((:tweetId IS NULL AND n.tweet.id IS NULL) OR n.tweet.id = :tweetId)
        """)
    int deleteInteraction(
            @Param("recipientId") UUID recipientId,
            @Param("actorId") UUID actorId,
            @Param("type") NotificationType type,
            @Param("tweetId") UUID tweetId);

    @Modifying
    @Query("""
        UPDATE NotificationEntity n
        SET n.isRead = true
        WHERE n.recipient.id = :recipientId
          AND n.isRead = false
        """)
    int markAllAsRead(@Param("recipientId") UUID recipientId);

    @Modifying
    @Query("""
        UPDATE NotificationEntity n
        SET n.isRead = true
        WHERE n.id = :id
        """)
    int markAsRead(@Param("id") UUID id);
}