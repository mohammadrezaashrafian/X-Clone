package logic_core.app.dto.response;

import logic_core.domain.model.notification.NotificationType;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Transport representation of a notification. {@code actor} is null when the
 * acting user no longer exists (the notification row is kept with a null
 * actor); {@code tweetId} is null for notifications without a tweet
 * reference (e.g. FOLLOW).
 */
public record NotificationResponse(
        UUID id,
        NotificationType type,
        UserSummaryResponse actor,
        UUID tweetId,
        boolean read,
        OffsetDateTime createdAt
) {}