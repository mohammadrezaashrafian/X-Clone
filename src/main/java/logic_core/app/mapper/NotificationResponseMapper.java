package logic_core.app.mapper;

import logic_core.app.dto.response.NotificationResponse;
import logic_core.app.dto.response.UserSummaryResponse;
import logic_core.domain.model.NotificationModel;

public final class NotificationResponseMapper
{
    private NotificationResponseMapper()
    {
    }

    public static NotificationResponse toResponse(
            NotificationModel model,
            UserSummaryResponse actor)
    {
        return new NotificationResponse(
                model.getId(),
                model.getType(),
                actor,
                model.getTweetId(),
                model.isRead(),
                model.getCreatedAt()
        );
    }
}