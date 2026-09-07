package logic_core.domain.model;

import logic_core.domain.model.notification.NotificationType;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Domain representation of a single notification. {@code actorId} and
 * {@code tweetId} are nullable: the actor may be removed from the database
 * (the row keeps existing with a null actor) and a notification does not have
 * to reference a tweet (e.g. FOLLOW).
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class NotificationModel
{
    private UUID id;
    private UUID recipientId;
    private UUID actorId;
    private UUID tweetId;
    private NotificationType type;
    private boolean isRead;
    private OffsetDateTime createdAt;

    public void markAsRead()
    {
        this.isRead = true;
    }

    public boolean isUnread()
    {
        return !isRead;
    }

    public boolean isSelfTriggered()
    {
        return actorId != null && recipientId != null
                && recipientId.equals(actorId);
    }

    public boolean hasTweetReference()
    {
        return tweetId != null;
    }
}