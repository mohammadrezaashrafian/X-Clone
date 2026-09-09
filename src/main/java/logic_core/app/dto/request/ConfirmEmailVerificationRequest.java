package logic_core.app.dto.request;

/**
 * Confirms the authenticated user's current email with the code they received
 * (EMAIL_VERIFY_CONFIRM, Issue #20). On success the account's
 * {@code email_verified} state is set to true.
 */
public record ConfirmEmailVerificationRequest(
        String sessionToken,
        String code
) {}