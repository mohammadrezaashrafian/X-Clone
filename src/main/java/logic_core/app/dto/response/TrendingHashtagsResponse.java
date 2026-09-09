package logic_core.app.dto.response;

import lombok.Builder;

import java.util.List;

/**
 * Trending hashtags for the supported window (TRENDING_HASHTAGS, [V2.1 #9]).
 *
 * <p>Supported window: the trailing 24 hours, as the half-open interval
 * {@code [windowStart, windowEnd)} on {@code tweets.published_at} — a tweet
 * published exactly at {@code windowStart} counts, exactly at
 * {@code windowEnd} does not.
 *
 * <p>Ranking formula: number of distinct qualifying tweets per hashtag
 * (soft-deleted tweets, deleted authors and retweet markers excluded),
 * descending, with the canonical tag ascending as the stable tie-breaker.
 * {@code rank} is the 1-based position in that deterministic order.
 */
@Builder
public record TrendingHashtagsResponse(
        List<TrendingHashtagItem> items,
        long totalItems,
        String windowDescription
) {
    /**
     * One trending hashtag entry.
     *
     * @param tag   canonical persisted hashtag (lowercase)
     * @param score number of distinct qualifying tweets inside the window
     * @param rank  1-based position in the deterministic ranking
     */
    public record TrendingHashtagItem(
            String tag,
            long score,
            int rank
    ) {}
}
