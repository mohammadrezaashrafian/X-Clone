package logic_core.domain.repository;

import logic_core.app.dto.timeline.TimelineTweet;
import logic_core.domain.model.HashtagFollow;
import logic_core.domain.model.HashtagModel;
import logic_core.domain.model.TrendingHashtag;
import logic_core.domain.model.TweetHashtag;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HashtagRepository
{
    // -------------------------------------------------------------------------
    // Hashtag lookup / persistence
    // -------------------------------------------------------------------------

    Optional<HashtagModel> findById(UUID hashtagId);

    /**
     * Lookup by the exact canonical (normalized) tag value.
     */
    Optional<HashtagModel> findByTag(String tag);

    /**
     * Batch lookup of existing hashtags by canonical tag value; missing tags
     * are simply absent from the result.
     */
    List<HashtagModel> findByTags(Collection<String> tags);

    /**
     * Persist a new hashtag row.
     */
    HashtagModel save(HashtagModel hashtag);

    /**
     * Persist the tweet-hashtag relationship (one row per distinct hashtag in
     * the tweet, with the occurrence count).
     */
    void attachToTweet(TweetHashtag relation);

    // -------------------------------------------------------------------------
    // Follow / unfollow
    // -------------------------------------------------------------------------

    boolean isFollowing(UUID userId, UUID hashtagId);

    Optional<HashtagFollow> findFollow(UUID userId, UUID hashtagId);

    void saveFollow(HashtagFollow follow);

    void deleteFollow(HashtagFollow follow);

    long countFollowers(UUID hashtagId);

    // -------------------------------------------------------------------------
    // Hashtag feed
    // -------------------------------------------------------------------------

    /**
     * Active tweets containing the hashtag, in timeline shape (author info +
     * interaction counts), applying the same visibility semantics as the home
     * timeline: the tweet author must not be blocked (either direction) or
     * muted by the actor, and the tweet must not be soft-deleted.
     */
    List<TimelineTweet> getTweetsByHashtag(UUID actorId, UUID hashtagId, int limit, int offset);

    /**
     * Count of active, visible tweets for the hashtag (same filters as
     * {@link #getTweetsByHashtag}).
     */
    long countTweetsByHashtag(UUID actorId, UUID hashtagId);

    // -------------------------------------------------------------------------
    // Search (HASHTAG_SEARCH)
    // -------------------------------------------------------------------------

    /**
     * Case-insensitive prefix search over the canonical persisted tag column,
     * deterministically ordered by tag ascending. Reads existing rows only —
     * never creates or finds-or-creates hashtags.
     *
     * @param prefix  already-canonical lowercase prefix from the use case
     * @param limit   page size (1..100)
     * @param offset  zero-based row offset (page * pageSize)
     */
    List<HashtagModel> searchByTagPrefix(String prefix, int limit, int offset);

    /**
     * Count of hashtags matching the prefix — same filter as
     * {@link #searchByTagPrefix}.
     */
    long countByTagPrefix(String prefix);

    // -------------------------------------------------------------------------
    // Trending (TRENDING_HASHTAGS, [V2.1 #9])
    // -------------------------------------------------------------------------

    /**
     * Trending hashtags inside the half-open window
     * {@code [windowStart, windowEnd)} on {@code tweets.published_at}.
     *
     * <p>Score = number of distinct qualifying tweets using the hashtag
     * (soft-deleted tweets, deleted authors and retweet markers excluded).
     * Database-aggregated; deterministic order {@code score DESC, tag ASC}.
     *
     * @param windowStart inclusive window start (inclusive boundary on
     *                    {@code published_at})
     * @param windowEnd   exclusive window end
     * @param limit       maximum number of rows to return (1..50)
     */
    List<TrendingHashtag> findTrending(
            java.time.OffsetDateTime windowStart,
            java.time.OffsetDateTime windowEnd,
            int limit);

    /**
     * Number of distinct hashtags with at least one qualifying relationship
     * inside the same window as {@link #findTrending} — the {@code totalItems}
     * of the trending response, taken against the identical window.
     */
    long countTrendingHashtags(
            java.time.OffsetDateTime windowStart,
            java.time.OffsetDateTime windowEnd);
}