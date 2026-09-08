package logic_core.infrastructure.repository;

import logic_core.infrastructure.persistence.entity.media.MediaEntity;
import logic_core.infrastructure.persistence.entity.tweet.TweetEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface MediaJpaRepository extends JpaRepository<MediaEntity, UUID> {

    /**
     * Find all media for a given tweet.
     * 
     * Equivalent to MediaDao.findByTweetId():
     * SELECT m FROM Media m WHERE m.tweet.id = :tweetId
     */
    @Query("""
            SELECT m
            FROM MediaEntity m
            WHERE m.tweet.id = :tweetId
            """)
    List<MediaEntity> findByTweetId(@Param("tweetId") UUID tweetId);

    /**
     * Find media by IDs, ordered by displayOrder.
     * 
     * Equivalent to MediaDao.findByIds():
     * SELECT m FROM Media m WHERE m.id IN :mediaIds ORDER BY m.displayOrder
     */
    @Query("""
            SELECT m
            FROM MediaEntity m
            WHERE m.id IN :mediaIds
            ORDER BY m.displayOrder
            """)
    List<MediaEntity> findByIds(@Param("mediaIds") List<UUID> mediaIds);

    /**
     * Find media attached to any of the given tweets, ordered by tweet and
     * displayOrder. Used by timeline/bookmark/hashtag projections to enrich
     * each {@code TimelineTweet} with its media in a single query (no N+1).
     */
    @Query("""
            SELECT m
            FROM MediaEntity m
            WHERE m.tweet.id IN :tweetIds
            ORDER BY m.tweet.id, m.displayOrder
            """)
    List<MediaEntity> findByTweetIds(@Param("tweetIds") Collection<UUID> tweetIds);

    /**
     * Atomically attaches an uploaded, unattached media row to a tweet.
     * Only rows without an existing tweet are updated; returns the number of
     * affected rows (0 when the media is already attached or does not exist).
     */
    @Modifying
    @Query("""
            UPDATE MediaEntity m
            SET m.tweet = :tweet
            WHERE m.id = :mediaId
              AND m.tweet IS NULL
            """)
    int attachToTweet(@Param("mediaId") UUID mediaId,
                      @Param("tweet") TweetEntity tweet);

    /**
     * Check if media belongs to a user and is currently unattached.
     *
     * <p>Verifies BOTH the media id and the {@code uploaded_by} owner, so an
     * actor can never be treated as owning another user's uploaded media.
     * The media must also be unattached, matching the upload-before-attach
     * lifecycle (V2.1 #6).
     */
    @Query("""
            SELECT CASE WHEN COUNT(m) > 0 THEN true ELSE false END
            FROM MediaEntity m
            WHERE m.id = :mediaId
              AND m.uploadedBy.id = :userId
              AND m.tweet IS NULL
            """)
    boolean belongsToUser(@Param("mediaId") UUID mediaId, @Param("userId") UUID userId);

    /**
     * Counts media uploaded by a user that are not yet attached to a tweet.
     * Used to assign the next display order for an upload (V2.1 #6), so that
     * attaching several uploaded media later preserves upload order.
     */
    @Query("""
            SELECT COUNT(m)
            FROM MediaEntity m
            WHERE m.uploadedBy.id = :userId
              AND m.tweet IS NULL
            """)
    long countUnattachedByUser(@Param("userId") UUID userId);

    /**
     * Check if media is already attached to a tweet.
     * 
     * Equivalent to MediaDao.isAlreadyAttached():
     * SELECT COUNT(m) FROM Media m WHERE m.id = :mediaId AND m.tweet IS NOT NULL
     */
    @Query("""
            SELECT CASE WHEN COUNT(m) > 0 THEN true ELSE false END
            FROM MediaEntity m
            WHERE m.id = :mediaId
              AND m.tweet IS NOT NULL
            """)
    boolean isAlreadyAttached(@Param("mediaId") UUID mediaId);

    /**
     * Hard-deletes all media records belonging to a tweet.
     * Used during tweet deletion to cascade-delete related media.
     */
    @Modifying
    @Query("DELETE FROM MediaEntity m WHERE m.tweet.id = :tweetId")
    void deleteByTweetId(@Param("tweetId") UUID tweetId);
}
