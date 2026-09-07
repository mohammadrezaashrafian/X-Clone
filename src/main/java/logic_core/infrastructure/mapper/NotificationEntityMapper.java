package logic_core.infrastructure.mapper;

import logic_core.domain.model.NotificationModel;
import logic_core.infrastructure.persistence.entity.notification.NotificationEntity;

public final class NotificationEntityMapper
{
    private NotificationEntityMapper()
    {
    }

    public static NotificationModel toModel(NotificationEntity entity)
    {
        if (entity == null)
        {
            return null;
        }

        return NotificationModel.builder()
                .id(entity.getId())
                .recipientId(
                        entity.getRecipient() != null
                                ? entity.getRecipient().getId()
                                : null
                )
                .actorId(
                        entity.getActor() != null
                                ? entity.getActor().getId()
                                : null
                )
                .tweetId(
                        entity.getTweet() != null
                                ? entity.getTweet().getId()
                                : null
                )
                .type(entity.getType())
                .isRead(entity.isRead())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}