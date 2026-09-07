package logic_core.domain.repository;

import logic_core.app.dto.timeline.TimelineTweet;
import logic_core.domain.model.HashtagFollow;
import logic_core.domain.model.HashtagModel;
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
}