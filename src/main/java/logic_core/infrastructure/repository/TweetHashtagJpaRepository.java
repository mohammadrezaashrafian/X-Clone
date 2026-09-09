package logic_core.infrastructure.repository;

import logic_core.infrastructure.persistence.entity.hashtag.TweetHashtagEntity;
import logic_core.infrastructure.persistence.entity.hashtag.TweetHashtagEntityId;
import logic_core.infrastructure.projection.TimelineTweetProjection;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TweetHashtagJpaRepository
        extends JpaRepository<TweetHashtagEntity, TweetHashtagEntityId>
{
    /**
     * Active tweets containing the hashtag, in timeline shape, applying the
     * same visibility semantics as the home timeline query: the tweet must
     * not be soft-deleted, the author must not be blocked (either direction)
     * by the actor, and the author must not be muted by the actor.
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
        FROM TweetHashtagEntity th
        JOIN th.tweet t
        JOIN t.author a
        LEFT JOIN t.likes l
        LEFT JOIN t.replies r
        LEFT JOIN t.retweets rt
        WHERE th.hashtag.id = :hashtagId
          AND t.isDeleted = false
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
    List<TimelineTweetProjection> findTweetsByHashtag(
            @Param("actorId") UUID actorId,
            @Param("hashtagId") UUID hashtagId,
            Pageable pageable
    );

    /**
     * Count of active tweets containing the hashtag that are visible to the
     * actor (same block/mute/soft-delete filters as the feed query).
     */
    @Query("""
        SELECT COUNT(t)
        FROM TweetHashtagEntity th
        JOIN th.tweet t
        JOIN t.author a
        WHERE th.hashtag.id = :hashtagId
          AND t.isDeleted = false
          AND NOT EXISTS (
              SELECT 1 FROM BlockEntity b
              WHERE (b.blocker.id = :actorId AND b.blocked.id = a.id)
                 OR (b.blocker.id = a.id AND b.blocked.id = :actorId)
          )
          AND a.id NOT IN (
              SELECT m.muted.id FROM MuteEntity m WHERE m.muter.id = :actorId
          )
        """)
    long countTweetsByHashtag(
            @Param("actorId") UUID actorId,
            @Param("hashtagId") UUID hashtagId
    );
}