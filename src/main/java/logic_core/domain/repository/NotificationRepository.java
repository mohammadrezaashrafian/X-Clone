package logic_core.domain.repository;

import logic_core.domain.model.NotificationModel;

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
}