package logic_core.app.dto.request;

/**
 * Requests a verification code for the authenticated user's current email
 * address (EMAIL_VERIFY_REQUEST, Issue #20). Self-service account protection:
 * registration/login are not gated on verification.
 */
public record RequestEmailVerificationRequest(
        String sessionToken
) {}