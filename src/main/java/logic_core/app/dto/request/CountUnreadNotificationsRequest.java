package logic_core.app.dto.request;

/**
 * Requests the authenticated user's unread notification count (V2.1 #7).
 */
public record CountUnreadNotificationsRequest(
        String sessionToken
) {}
