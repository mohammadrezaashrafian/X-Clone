package logic_core.app.dto.response;

/**
 * The authenticated user's unread notification count (V2.1 #7).
 */
public record UnreadNotificationsCountResponse(
        long unreadCount
) {}
