package logic_core.domain.repository;

import logic_core.domain.model.NotificationModel;
import logic_core.domain.model.notification.NotificationType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain contract for notification persistence. Mirrors the legacy
 * {@code NotificationRepository} shape (findById / findByReceiverId /
 * save / markAllAsRead).
 */
public interface NotificationRepository
{
    Optional<NotificationModel> findById(UUID id);

    /**
     * Returns the recipient's notifications, newest first.
     */
    List<NotificationModel> findByRecipientId(UUID recipientId);

    void save(NotificationModel notificationModel);

    /**
     * Marks every unread notification of the recipient as read.
     *
     * @return the number of rows updated
     */
    int markAllAsRead(UUID recipientId);

    /**
     * Marks a single notification as read.
     *
     * @return the number of rows updated (0 when the notification does not
     *         exist)
     */
    int markAsRead(UUID id);

    /**
     * One page of the recipient's notifications, newest first. Backs the
     * paginated notification read model (V2.1 #7).
     */
    List<NotificationModel> findPageByRecipientId(UUID recipientId, int page, int pageSize);

    /**
     * Total number of the recipient's notifications (read and unread), for
     * page metadata (V2.1 #7).
     */
    long countByRecipientId(UUID recipientId);

    /**
     * Number of the recipient's unread notifications. Backs the unread-count
     * endpoint (V2.1 #7).
     */
    long countUnreadByRecipientId(UUID recipientId);

    /**
     * Hard-deletes the notification generated for one interaction so undoing
     * the interaction (unlike / unfollow / unretweet) removes the
     * corresponding notification instead of leaving a ghost.
     *
     * @return the number of rows deleted
     */
    int deleteInteraction(UUID recipientId, UUID actorId, NotificationType type, UUID tweetId);
}