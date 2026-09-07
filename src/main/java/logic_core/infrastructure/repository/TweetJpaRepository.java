package logic_core.infrastructure.repository;

import logic_core.infrastructure.persistence.entity.tweet.TweetEntity;
import logic_core.infrastructure.projection.TimelineTweetProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TweetJpaRepository extends JpaRepository<TweetEntity, UUID> {

    // -------------------------------------------------------------------------
    // Basic reads
    // -------------------------------------------------------------------------

    Optional<TweetEntity> findByIdAndIsDeletedFalse(UUID id);

    List<TweetEntity> findByAuthorIdOrderByCreatedAtDesc(UUID authorId);

    List<TweetEntity> findByAuthorIdAndIsDeletedFalseOrderByCreatedAtDesc(UUID authorId);

    // -------------------------------------------------------------------------
    // Reply / Retweet / Quote
    // -------------------------------------------------------------------------

    List<TweetEntity> findByReplyToIdAndIsDeletedFalseOrderByCreatedAtAsc(UUID tweetId);

    List<TweetEntity> findByRetweetOfIdAndIsDeletedFalseOrderByCreatedAtDesc(UUID tweetId);

    List<TweetEntity> findByQuoteOfIdAndIsDeletedFalseOrderByCreatedAtDesc(UUID tweetId);


    // -------------------------------------------------------------------------
    // Counts
    // -------------------------------------------------------------------------

    long countByReplyToIdAndIsDeletedFalse(UUID tweetId);

    long countByRetweetOfIdAndIsDeletedFalse(UUID tweetId);

    long countByAuthorIdAndIsDeletedFalse(UUID authorId);


    // -------------------------------------------------------------------------
    // Existence
    // -------------------------------------------------------------------------

    boolean existsByIdAndIsDeletedFalse(UUID id);

    // -------------------------------------------------------------------------
    // User interactions
    // -------------------------------------------------------------------------

    @Query("""
    SELECT CASE WHEN COUNT(t) > 0 THEN true ELSE false END
    FROM TweetEntity t
    WHERE t.replyTo.id = :tweetId
      AND t.author.id = :userId
      AND t.isDeleted = false
    """)
    boolean isRepliedByUser(
            @Param("tweetId") UUID tweetId,
            @Param("userId") UUID userId
    );

    @Query("""
        SELECT CASE WHEN COUNT(t) > 0 THEN true ELSE false END
        FROM TweetEntity t
        WHERE t.retweetOf.id = :tweetId
          AND t.author.id = :userId
          AND t.isDeleted = false
        """)
    boolean isRetweetedByUser(@Param("tweetId") UUID tweetId, @Param("userId") UUID userId);

    /**
     * Hard-deletes exactly the retweet marker rows created by {@code userId}
     * for the original tweet {@code tweetId} that are still active (not
     * soft-deleted). Returns the number of removed rows (0 or 1).
     *
     * <p>Only the marker row itself is removed — the original tweet, other
     * users' retweets, and unrelated retweets are never touched. Retweet
     * counts are derived from the active retweet rows, so no separate counter
     * update is needed: removing the row atomically decrements the derived
     * count by exactly one.
     */
    @Modifying
    @Query("""
        DELETE FROM TweetEntity t
        WHERE t.retweetOf.id = :tweetId
          AND t.author.id = :userId
          AND t.isDeleted = false
        """)
    int deleteActiveRetweetByUser(
            @Param("tweetId") UUID tweetId,
            @Param("userId") UUID userId
    );

    // -------------------------------------------------------------------------
    // User activity
    // -------------------------------------------------------------------------

    /**
     * Returns the original tweets that the user replied to.
     *
     * Equivalent to the old:
     *
     * SELECT DISTINCT t.repliedToTweet
     * FROM Tweet t
     * WHERE t.author.id = :userId
     *   AND t.repliedToTweet IS NOT NULL
     *   AND t.isDeleted = false
     *   AND t.repliedToTweet.isDeleted = false
     * ORDER BY t.repliedToTweet.createdAt DESC
     */
    @Query("""
    SELECT DISTINCT t.replyTo
    FROM TweetEntity t
    WHERE t.author.id = :userId
      AND t.replyTo IS NOT NULL
      AND t.isDeleted = false
      AND t.replyTo.isDeleted = false
    ORDER BY t.replyTo.createdAt DESC
    """)
    List<TweetEntity> findTweetsRepliedByUser(@Param("userId") UUID userId);

    /**
     * Returns the original tweets that the user retweeted.
     */
    @Query("""
        SELECT DISTINCT t.retweetOf
        FROM TweetEntity t
        WHERE t.author.id = :userId
          AND t.retweetOf IS NOT NULL
          AND t.isDeleted = false
          AND t.retweetOf.isDeleted = false
        ORDER BY t.retweetOf.createdAt DESC
        """)
    List<TweetEntity> findTweetsRetweetedByUser(@Param("userId") UUID userId);

    // -------------------------------------------------------------------------
    // Simple home/timeline candidates
    // -------------------------------------------------------------------------

    /**
     * Simple timeline candidate query.
     *
     * This is the equivalent of the old TweetDao.findTimelineTweets().
     *
     * Block/mute filtering is NOT applied here because the old method
     * did not apply those filters either.
     */
    @Query("""
        SELECT t
        FROM TweetEntity t
        WHERE t.isDeleted = false
          AND (
              t.author.id = :userId
              OR              t.author.id IN (
                  SELECT f.following.id
                  FROM FollowEntity f
                  WHERE f.follower.id = :userId
              )
          )
        ORDER BY t.createdAt DESC
        """)
    List<TweetEntity> findTimelineTweets(@Param("userId") UUID userId);


    // -------------------------------------------------------------------------
    // Pessimistic locking
    // -------------------------------------------------------------------------

    /**
     * Equivalent to the old:
     *
     * query.setLockMode(LockModeType.PESSIMISTIC_WRITE)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT t
        FROM TweetEntity t
        WHERE t.id = :tweetId
          AND t.isDeleted = false
        """)
    Optional<TweetEntity> findActiveByIdForUpdate(@Param("tweetId") UUID tweetId);

    // =========================================================================
    // Timeline — HOME
    // =========================================================================

    @Query("""
        SELECT new logic_core.infrastructure.projection.TimelineTweetProjection(
            t.id, a.id, a.username, a.displayName, a.avatarUrl,
            t.content,
            COUNT(DISTINCT l.user),
            COUNT(DISTINCT r),
            COUNT(DISTINCT rt),
            t.publishedAt
        )
        FROM TweetEntity t
        JOIN t.author a
        LEFT JOIN t.likes l
        LEFT JOIN t.replies r
        LEFT JOIN t.retweets rt
        WHERE t.isDeleted = false
          AND (
              a.id = :actorId
              OR a.id IN (
                  SELECT f.following.id
                  FROM FollowEntity f
                  WHERE f.follower.id = :actorId
              )
          )
          AND NOT EXISTS (
              SELECT 1 FROM BlockEntity b
              WHERE (b.blocker.id = :actorId AND b.blocked.id = a.id)
                 OR (b.blocker.id = a.id AND b.blocked.id = :actorId)
          )
          AND a.id NOT IN (
              SELECT m.muted.id FROM MuteEntity m WHERE m.muter.id = :actorId
          )
        GROUP BY t.id, a.id, a.username, a.displayName, a.avatarUrl, t.content, t.publishedAt
        ORDER BY t.publishedAt DESC
        """)
    List<TimelineTweetProjection> findHomeTimeline(
            @Param("actorId") UUID actorId,
            org.springframework.data.domain.Pageable pageable
    );

    @Query("""
        SELECT COUNT(t)
        FROM TweetEntity t
        JOIN t.author a
        WHERE t.isDeleted = false
          AND (
              a.id = :actorId
              OR a.id IN (
                  SELECT f.following.id
                  FROM FollowEntity f
                  WHERE f.follower.id = :actorId
              )
          )
          AND NOT EXISTS (
              SELECT 1 FROM BlockEntity b
              WHERE (b.blocker.id = :actorId AND b.blocked.id = a.id)
                 OR (b.blocker.id = a.id AND b.blocked.id = :actorId)
          )
          AND a.id NOT IN (
              SELECT m.muted.id FROM MuteEntity m WHERE m.muter.id = :actorId
          )
        """)
    long countHomeTimeline(@Param("actorId") UUID actorId);

    // =========================================================================
    // Timeline — FOLLOWING
    // =========================================================================

    @Query("""
        SELECT new logic_core.infrastructure.projection.TimelineTweetProjection(
            t.id, a.id, a.username, a.displayName, a.avatarUrl,
            t.content,
            COUNT(DISTINCT l.user),
            COUNT(DISTINCT r),
            COUNT(DISTINCT rt),
            t.publishedAt
        )
        FROM TweetEntity t
        JOIN t.author a
        LEFT JOIN t.likes l
        LEFT JOIN t.replies r
        LEFT JOIN t.retweets rt
        WHERE t.isDeleted = false
          AND a.id IN (
              SELECT f.following.id
              FROM FollowEntity f
              WHERE f.follower.id = :actorId
          )
          AND NOT EXISTS (
              SELECT 1 FROM BlockEntity b
              WHERE (b.blocker.id = :actorId AND b.blocked.id = a.id)
                 OR (b.blocker.id = a.id AND b.blocked.id = :actorId)
          )
          AND a.id NOT IN (
              SELECT m.muted.id FROM MuteEntity m WHERE m.muter.id = :actorId
          )
        GROUP BY t.id, a.id, a.username, a.displayName, a.avatarUrl, t.content, t.publishedAt
        ORDER BY t.publishedAt DESC
        """)
    List<TimelineTweetProjection> findFollowingTimeline(
            @Param("actorId") UUID actorId,
            org.springframework.data.domain.Pageable pageable
    );

    @Query("""
        SELECT COUNT(t)
        FROM TweetEntity t
        JOIN t.author a
        WHERE t.isDeleted = false
          AND a.id IN (
              SELECT f.following.id
              FROM FollowEntity f
              WHERE f.follower.id = :actorId
          )
          AND NOT EXISTS (
              SELECT 1 FROM BlockEntity b
              WHERE (b.blocker.id = :actorId AND b.blocked.id = a.id)
                 OR (b.blocker.id = a.id AND b.blocked.id = :actorId)
          )
          AND a.id NOT IN (
              SELECT m.muted.id FROM MuteEntity m WHERE m.muter.id = :actorId
          )
        """)
    long countFollowingTimeline(@Param("actorId") UUID actorId);

    // =========================================================================
    // Timeline — REPLIES
    // =========================================================================

    @Query("""
        SELECT new logic_core.infrastructure.projection.TimelineTweetProjection(
            t.id, a.id, a.username, a.displayName, a.avatarUrl,
            t.content,
            COUNT(DISTINCT l.user),
            COUNT(DISTINCT r),
            COUNT(DISTINCT rt),
            t.publishedAt
        )
        FROM TweetEntity t
        JOIN t.author a
        LEFT JOIN t.likes l
        LEFT JOIN t.replies r
        LEFT JOIN t.retweets rt
        WHERE t.isDeleted = false
          AND a.id = :targetUserId
          AND t.replyTo IS NOT NULL
          AND NOT EXISTS (
              SELECT 1 FROM BlockEntity b
              WHERE (b.blocker.id = :actorId AND b.blocked.id = a.id)
                 OR (b.blocker.id = a.id AND b.blocked.id = :actorId)
          )
        GROUP BY t.id, a.id, a.username, a.displayName, a.avatarUrl, t.content, t.publishedAt
        ORDER BY t.publishedAt DESC
        """)
    List<TimelineTweetProjection> findRepliesTimeline(
            @Param("actorId") UUID actorId,
            @Param("targetUserId") UUID targetUserId,
            org.springframework.data.domain.Pageable pageable
    );

    @Query("""
        SELECT COUNT(t)
        FROM TweetEntity t
        JOIN t.author a
        WHERE t.isDeleted = false
          AND a.id = :targetUserId
          AND t.replyTo IS NOT NULL
          AND NOT EXISTS (
              SELECT 1 FROM BlockEntity b
              WHERE (b.blocker.id = :actorId AND b.blocked.id = a.id)
                 OR (b.blocker.id = a.id AND b.blocked.id = :actorId)
          )
        """)
    long countRepliesTimeline(
            @Param("actorId") UUID actorId,
            @Param("targetUserId") UUID targetUserId
    );

    // =========================================================================
    // Timeline — MEDIA
    // =========================================================================

    @Query("""
        SELECT new logic_core.infrastructure.projection.TimelineTweetProjection(
            t.id, a.id, a.username, a.displayName, a.avatarUrl,
            t.content,
            COUNT(DISTINCT l.user),
            COUNT(DISTINCT r),
            COUNT(DISTINCT rt),
            t.publishedAt
        )
        FROM TweetEntity t
        JOIN t.author a
        LEFT JOIN t.likes l
        LEFT JOIN t.replies r
        LEFT JOIN t.retweets rt
        WHERE t.isDeleted = false
          AND a.id = :targetUserId
          AND EXISTS (
              SELECT 1 FROM MediaEntity m WHERE m.tweet = t
          )
          AND NOT EXISTS (
              SELECT 1 FROM BlockEntity b
              WHERE (b.blocker.id = :actorId AND b.blocked.id = a.id)
                 OR (b.blocker.id = a.id AND b.blocked.id = :actorId)
          )
        GROUP BY t.id, a.id, a.username, a.displayName, a.avatarUrl, t.content, t.publishedAt
        ORDER BY t.publishedAt DESC
        """)
    List<TimelineTweetProjection> findMediaTimeline(
            @Param("actorId") UUID actorId,
            @Param("targetUserId") UUID targetUserId,
            org.springframework.data.domain.Pageable pageable
    );

    @Query("""
        SELECT COUNT(t)
        FROM TweetEntity t
        JOIN t.author a
        WHERE t.isDeleted = false
          AND a.id = :targetUserId
          AND EXISTS (
              SELECT 1 FROM MediaEntity m WHERE m.tweet = t
          )
          AND NOT EXISTS (
              SELECT 1 FROM BlockEntity b
              WHERE (b.blocker.id = :actorId AND b.blocked.id = a.id)
                 OR (b.blocker.id = a.id AND b.blocked.id = :actorId)
          )
        """)
    long countMediaTimeline(
            @Param("actorId") UUID actorId,
            @Param("targetUserId") UUID targetUserId
    );

    // =========================================================================
    // Timeline — USER
    // =========================================================================

    @Query("""
        SELECT new logic_core.infrastructure.projection.TimelineTweetProjection(
            t.id, a.id, a.username, a.displayName, a.avatarUrl,
            t.content,
            COUNT(DISTINCT l.user),
            COUNT(DISTINCT r),
            COUNT(DISTINCT rt),
            t.publishedAt
        )
        FROM TweetEntity t
        JOIN t.author a
        LEFT JOIN t.likes l
        LEFT JOIN t.replies r
        LEFT JOIN t.retweets rt
        WHERE t.isDeleted = false
          AND a.id = :targetUserId
          AND NOT EXISTS (
              SELECT 1 FROM BlockEntity b
              WHERE (b.blocker.id = :actorId AND b.blocked.id = a.id)
                 OR (b.blocker.id = a.id AND b.blocked.id = :actorId)
          )
        GROUP BY t.id, a.id, a.username, a.displayName, a.avatarUrl, t.content, t.publishedAt
        ORDER BY t.publishedAt DESC
        """)
    List<TimelineTweetProjection> findUserTimeline(
            @Param("actorId") UUID actorId,
            @Param("targetUserId") UUID targetUserId,
            org.springframework.data.domain.Pageable pageable
    );

    @Query("""
        SELECT COUNT(t)
        FROM TweetEntity t
        JOIN t.author a
        WHERE t.isDeleted = false
          AND a.id = :targetUserId
          AND NOT EXISTS (
              SELECT 1 FROM BlockEntity b
              WHERE (b.blocker.id = :actorId AND b.blocked.id = a.id)
                 OR (b.blocker.id = a.id AND b.blocked.id = :actorId)
          )
        """)
    long countUserTimeline(
            @Param("actorId") UUID actorId,
            @Param("targetUserId") UUID targetUserId
    );

    // =========================================================================
    // Timeline — LIKED
    // =========================================================================

    @Query("""
        SELECT new logic_core.infrastructure.projection.TimelineTweetProjection(
            t.id, a.id, a.username, a.displayName, a.avatarUrl,
            t.content,
            COUNT(DISTINCT l.user),
            COUNT(DISTINCT r),
            COUNT(DISTINCT rt),
            t.publishedAt
        )
        FROM TweetEntity t
        JOIN t.author a
        LEFT JOIN t.likes l
        LEFT JOIN t.replies r
        LEFT JOIN t.retweets rt
        JOIN t.likes liked
        WHERE t.isDeleted = false
          AND liked.user.id = :targetUserId
          AND NOT EXISTS (
              SELECT 1 FROM BlockEntity b
              WHERE (b.blocker.id = :actorId AND b.blocked.id = a.id)
                 OR (b.blocker.id = a.id AND b.blocked.id = :actorId)
          )
        GROUP BY t.id, a.id, a.username, a.displayName, a.avatarUrl, t.content, t.publishedAt
        ORDER BY t.publishedAt DESC
        """)
    List<TimelineTweetProjection> findLikedTimeline(
            @Param("actorId") UUID actorId,
            @Param("targetUserId") UUID targetUserId,
            org.springframework.data.domain.Pageable pageable
    );

    @Query("""
        SELECT COUNT(DISTINCT t)
        FROM TweetEntity t
        JOIN t.author a
        JOIN t.likes liked
        WHERE t.isDeleted = false
          AND liked.user.id = :targetUserId
          AND NOT EXISTS (
              SELECT 1 FROM BlockEntity b
              WHERE (b.blocker.id = :actorId AND b.blocked.id = a.id)
                 OR (b.blocker.id = a.id AND b.blocked.id = :actorId)
          )
        """)
    long countLikedTimeline(
            @Param("actorId") UUID actorId,
            @Param("targetUserId") UUID targetUserId
    );

    // =========================================================================
    // Replies thread (TWEET_GET_REPLIES)
    // =========================================================================

    @Query("""
        SELECT new logic_core.infrastructure.projection.TimelineTweetProjection(
            t.id, a.id, a.username, a.displayName, a.avatarUrl,
            t.content,
            COUNT(DISTINCT l.user),
            COUNT(DISTINCT r),
            COUNT(DISTINCT rt),
            t.publishedAt
        )
        FROM TweetEntity t
        JOIN t.author a
        LEFT JOIN t.likes l
        LEFT JOIN t.replies r
        LEFT JOIN t.retweets rt
        WHERE t.isDeleted = false
          AND t.replyTo.id = :tweetId
          AND NOT EXISTS (
              SELECT 1 FROM BlockEntity b
              WHERE (b.blocker.id = :actorId AND b.blocked.id = a.id)
                 OR (b.blocker.id = a.id AND b.blocked.id = :actorId)
          )
        GROUP BY t.id, a.id, a.username, a.displayName, a.avatarUrl, t.content, t.publishedAt
        ORDER BY t.publishedAt ASC, t.id ASC
        """)
    List<TimelineTweetProjection> findRepliesOfTweet(
            @Param("actorId") UUID actorId,
            @Param("tweetId") UUID tweetId
    );

    // =========================================================================
    // Single tweet retrieval (TWEET_GET)
    // =========================================================================

    /**
     * Loads one active (non-deleted) tweet in timeline shape with author info
     * and interaction counts, but only when the {@code actorId} is not blocked
     * (either direction) by the tweet author — the same block visibility
     * semantics the timeline and reply-thread queries apply.
     *
     * @return empty when the tweet does not exist, is soft-deleted, or is not
     *         visible to the actor due to a block relation in either direction
     */
    @Query("""
        SELECT new logic_core.infrastructure.projection.TimelineTweetProjection(
            t.id, a.id, a.username, a.displayName, a.avatarUrl,
            t.content,
            COUNT(DISTINCT l.user),
            COUNT(DISTINCT r),
            COUNT(DISTINCT rt),
            t.publishedAt
        )
        FROM TweetEntity t
        JOIN t.author a
        LEFT JOIN t.likes l
        LEFT JOIN t.replies r
        LEFT JOIN t.retweets rt
        WHERE t.isDeleted = false
          AND t.id = :tweetId
          AND NOT EXISTS (
              SELECT 1 FROM BlockEntity b
              WHERE (b.blocker.id = :actorId AND b.blocked.id = a.id)
                 OR (b.blocker.id = a.id AND b.blocked.id = :actorId)
          )
        GROUP BY t.id, a.id, a.username, a.displayName, a.avatarUrl, t.content, t.publishedAt
        """)
    Optional<TimelineTweetProjection> findSingleTweetForActor(
            @Param("actorId") UUID actorId,
            @Param("tweetId") UUID tweetId
    );
}