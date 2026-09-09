package logic_core.domain.model;

/**
 * Domain read model for one trending hashtag ([V2.1 #9] Trending
 * Foundation): the canonical persisted tag and its trending score.
 *
 * <p>The score is computed by the repository as the number of distinct
 * qualifying tweets that used the tag inside the supported window
 * (trailing 24 hours, half-open {@code [windowStart, windowEnd)} on
 * {@code tweets.published_at}), with soft-deleted tweets, deleted authors
 * and retweet markers excluded. Ranking order — score descending, tag
 * ascending as the stable tie-breaker — is applied by the repository query
 * itself, so repeated calls return identical order for identical data.
 */
public record TrendingHashtag(
        String tag,
        long score
) {}
