package logic_core.app.service;

import logic_core.domain.model.NotificationModel;
import logic_core.domain.model.notification.NotificationType;
import logic_core.domain.repository.NotificationRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Creates notifications on behalf of the existing interaction use cases.
 *
 * <p>This is a plain service call — deliberately NOT an event bus or message
 * broker. Each interaction use case invokes {@link #notify(...)} in the same
 * transaction after the interaction has been persisted, so a notification is
 * written only when the interaction itself succeeded (and its block/mute
 * authorization already passed).
 *
 * <p>Self-interactions (recipient == actor) are silently skipped: a user is
 * never notified about their own like/reply/retweet/quote/follow.
 */
@Service
@RequiredArgsConstructor
public class NotificationApplicationService
{
    @NonNull private final NotificationRepository notificationRepository;

    public void notify(
            UUID recipientId,
            UUID actorId,
            UUID tweetId,
            NotificationType type)
    {
        if (recipientId == null
                || actorId == null
                || type == null
                || recipientId.equals(actorId))
        {
            return;
        }

        NotificationModel notification = NotificationModel.builder()
                .recipientId(recipientId)
                .actorId(actorId)
                .tweetId(tweetId)
                .type(type)
                .isRead(false)
                .createdAt(OffsetDateTime.now())
                .build();

        notificationRepository.save(notification);
    }
}