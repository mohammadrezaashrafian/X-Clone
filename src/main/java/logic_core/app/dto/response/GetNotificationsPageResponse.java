package logic_core.app.dto.response;

import java.util.List;

/**
 * Paginated page of the authenticated user's notifications, newest first
 * (V2.1 #7).
 */
public record GetNotificationsPageResponse(
        List<NotificationResponse> notifications,
        long totalItems,
        int page,
        int pageSize,
        boolean hasNext
) {}
