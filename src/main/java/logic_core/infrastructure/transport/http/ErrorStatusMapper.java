package logic_core.infrastructure.transport.http;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Central, deterministic mapping from the standardized {@code errorCode} values
 * carried by failed {@link logic_core.infrastructure.transport.ResponseEnvelope}s
 * to HTTP status codes.
 *
 * <p>Route-specific business failure codes (for example {@code TWEET_LIKE_FAILED})
 * are intentionally not listed here: they remain HTTP 200, matching the existing
 * transport contract where the envelope {@code success} flag is the authoritative
 * signal. Only the standardized typed codes below receive dedicated statuses.
 */
public final class ErrorStatusMapper
{
    private ErrorStatusMapper()
    {
    }

    /**
     * @param errorCode the envelope {@code errorCode}, or {@code null} for
     *                  successful envelopes
     * @return the HTTP status for the given code; 200 for {@code null} and for
     *         route-specific business failure codes
     */
    public static int statusFor(String errorCode)
    {
        if (errorCode == null)
        {
            return HttpServletResponse.SC_OK;
        }

        return switch (errorCode)
        {
            case "MALFORMED_JSON", "VALIDATION_ERROR", "UNKNOWN_REQUEST" ->
                    HttpServletResponse.SC_BAD_REQUEST;

            case "AUTH_REQUIRED", "UNAUTHORIZED" ->
                    HttpServletResponse.SC_UNAUTHORIZED;

            case "FORBIDDEN" -> HttpServletResponse.SC_FORBIDDEN;

            case "NOT_FOUND" -> HttpServletResponse.SC_NOT_FOUND;

            case "CONFLICT" -> HttpServletResponse.SC_CONFLICT;

            case "UNSUPPORTED_MEDIA_TYPE" ->
                    HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE;

            case "DATABASE_ERROR", "UNEXPECTED_ERROR" ->
                    HttpServletResponse.SC_INTERNAL_SERVER_ERROR;

            default -> HttpServletResponse.SC_OK;
        };
    }
}