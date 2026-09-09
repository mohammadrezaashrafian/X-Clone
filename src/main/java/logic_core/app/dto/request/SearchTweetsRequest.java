package logic_core.app.dto.request;

/**
 * Request for case-insensitive substring search over tweet content
 * (TWEET_SEARCH).
 *
 * <p>The authenticated actor is derived from {@code sessionToken} server-side;
 * the actor is never taken from a caller-supplied field.
 *
 * @param page     zero-based page index
 * @param pageSize items per page, clamped server-side (1..100)
 */
public record SearchTweetsRequest(
        String query,
        int page,
        int pageSize,
        String sessionToken
) {}
