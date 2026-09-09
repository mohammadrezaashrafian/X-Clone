package logic_core.infrastructure.projection;

/**
 * Aggregated trending score for one hashtag, produced by the trending
 * repository query ([V2.1 #9] Trending Foundation).
 *
 * <p>The score is the number of qualifying tweet-hashtag relationships —
 * i.e. distinct tweets — whose tweets were published inside the trending
 * window and satisfy the lifecycle filters (tweet not soft-deleted, author
 * not deleted, retweet markers excluded). {@code usage_count} (occurrences
 * of the tag within one tweet) is intentionally not summed: the trending
 * score counts distinct tweets, not in-tweet repetitions.
 *
 * @param tag   canonical persisted hashtag (lowercase)
 * @param score number of qualifying tweets inside the window
 */
public record TrendingHashtagProjection(
        String tag,
        long score
) {}
