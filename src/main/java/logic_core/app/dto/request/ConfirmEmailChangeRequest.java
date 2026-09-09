package logic_core.app.dto.request;

/**
 * Confirms a pending email change with the code sent to the <b>new</b>
 * address (EMAIL_CHANGE_CONFIRM, Issue #20). The pending address comes from
 * the authenticated user's account (set by USER_UPDATE_EMAIL); the old email
 * remains authoritative until this confirmation succeeds.
 */
public record ConfirmEmailChangeRequest(
        String sessionToken,
        String code
) {}