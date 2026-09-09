package logic_core.app.dto.request;

/**
 * Request for the trending-hashtags ranking (TRENDING_HASHTAGS, [V2.1 #9]).
 *
 * <p>The authenticated actor is derived from {@code sessionToken}
 * server-side. Trending is a global ranking — no per-actor visibility
 * filtering applies.
 *
 * @param limit maximum number of results, clamped server-side (1..50);
 *              {@code null} uses the default of 10
 */
public record GetTrendingHashtagsRequest(
        String sessionToken,
        Integer limit
) {}
