package logic_core.app.dto.request;

/**
 * Returns the authenticated user's notifications, newest first. The recipient
 * is derived from the session — never from a caller-supplied field.
 *
 * @param page     zero-based page index (V2.1 #7); optional for backwards
 *                 compatibility
 * @param pageSize items per page, clamped server-side (V2.1 #7)
 */
public record GetNotificationsRequest(
        String sessionToken,
        Integer page,
        Integer pageSize
) {
    /** Legacy single-argument construction (no pagination). */
    public GetNotificationsRequest(String sessionToken) {
        this(sessionToken, null, null);
    }
}