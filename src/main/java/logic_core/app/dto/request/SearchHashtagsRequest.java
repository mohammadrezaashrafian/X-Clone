package logic_core.app.dto.request;

/**
 * Request for case-insensitive prefix search over persisted hashtags
 * (HASHTAG_SEARCH).
 *
 * <p>The authenticated actor is derived from {@code sessionToken} server-side.
 * Search is read-only — it never creates hashtag rows.
 *
 * @param page     zero-based page index
 * @param pageSize items per page, clamped server-side (1..100)
 */
public record SearchHashtagsRequest(
        String query,
        int page,
        int pageSize,
        String sessionToken
) {}
