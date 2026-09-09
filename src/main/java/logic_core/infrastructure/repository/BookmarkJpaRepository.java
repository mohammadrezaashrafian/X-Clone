package logic_core.infrastructure.repository;

import logic_core.infrastructure.persistence.entity.bookmark.BookmarkEntity;
import logic_core.infrastructure.persistence.entity.bookmark.BookmarkEntityId;
import logic_core.infrastructure.projection.TimelineTweetProjection;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BookmarkJpaRepository
        extends JpaRepository<BookmarkEntity, BookmarkEntityId>
{
    boolean existsByUser_IdAndTweet_Id(UUID userId, UUID tweetId);

    Optional<BookmarkEntity> findByUser_IdAndTweet_Id(UUID userId, UUID tweetId);

    /**
     * Hard-deletes all bookmark records belonging to a tweet. Used during tweet
     * deletion to cascade-delete related bookmarks alongside likes, media,
     * mentions and edit history.
     */
    @Modifying
    @Query("DELETE FROM BookmarkEntity bm WHERE bm.tweet.id = :tweetId")
    void deleteByTweetId(@Param("tweetId") UUID tweetId);

    /**
     * The user's own bookmarked tweets, newest bookmark first, in timeline
     * shape, applying the same visibility semantics as the timeline queries:
     * the tweet must not be soft-deleted, the author must not be blocked
     * (either direction) or muted by the viewer. Only the bookmark owner's
     * rows are ever considered — bookmarks are private.
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
        FROM BookmarkEntity bm
        JOIN bm.tweet t
        JOIN t.author a
        LEFT JOIN t.likes l
        LEFT JOIN t.replies r
        LEFT JOIN t.retweets rt
        WHERE bm.user.id = :userId
          AND t.isDeleted = false
          AND NOT EXISTS (
              SELECT 1 FROM BlockEntity b
              WHERE (b.blocker.id = :userId AND b.blocked.id = a.id)
                 OR (b.blocker.id = a.id AND b.blocked.id = :userId)
          )
          AND a.id NOT IN (
              SELECT m.muted.id FROM MuteEntity m WHERE m.muter.id = :userId
          )
        GROUP BY t.id, a.id, a.username, a.displayName, a.avatarUrl, t.content, t.publishedAt, bm.createdAt
        ORDER BY bm.createdAt DESC, t.id DESC
        """)
    List<TimelineTweetProjection> findBookmarksForUser(
            @Param("userId") UUID userId,
            Pageable pageable
    );

    /**
     * Count of the user's visible bookmarked tweets (same block/mute/soft-delete
     * filters as the bookmark-list query).
     */
    @Query("""
        SELECT COUNT(t)
        FROM BookmarkEntity bm
        JOIN bm.tweet t
        JOIN t.author a
        WHERE bm.user.id = :userId
          AND t.isDeleted = false
          AND NOT EXISTS (
              SELECT 1 FROM BlockEntity b
              WHERE (b.blocker.id = :userId AND b.blocked.id = a.id)
                 OR (b.blocker.id = a.id AND b.blocked.id = :userId)
          )
          AND a.id NOT IN (
              SELECT m.muted.id FROM MuteEntity m WHERE m.muter.id = :userId
          )
        """)
    long countBookmarksForUser(@Param("userId") UUID userId);
}