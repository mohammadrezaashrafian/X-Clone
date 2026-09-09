package logic_core.infrastructure.repository;

import logic_core.infrastructure.persistence.entity.hashtag.HashtagEntity;
import logic_core.infrastructure.projection.TrendingHashtagProjection;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface HashtagJpaRepository extends JpaRepository<HashtagEntity, UUID>
{
    Optional<HashtagEntity> findByTag(String tag);

    List<HashtagEntity> findByTagIn(Collection<String> tags);

    /**
     * Case-insensitive prefix search over the canonical (persisted) tag
     * column, ordered deterministically by tag ascending. {@code prefix} must
     * be the already-normalized lowercase prefix with the LIKE escape
     * character applied by the adapter (see
     * {@code HashtagRepositoryAdapter#toPrefixLikePattern}); the ESCAPE clause
     * below must stay in sync with that escape character.
     */
    @Query("""
            SELECT h FROM HashtagEntity h
            WHERE LOWER(h.tag) LIKE :prefix ESCAPE '\\'
            ORDER BY h.tag ASC
            """)
    List<HashtagEntity> searchByTagPrefix(@Param("prefix") String prefix, Pageable pageable);

    /**
     * Count of hashtags matching the prefix — same filter as
     * {@link #searchByTagPrefix}.
     */
    @Query("SELECT COUNT(h) FROM HashtagEntity h WHERE LOWER(h.tag) LIKE :prefix ESCAPE '\\'")
    long countByTagPrefix(@Param("prefix") String prefix);

    // =========================================================================
    // Trending (TRENDING_HASHTAGS, [V2.1 #9])
    // =========================================================================

    /**
     * Trending hashtags inside the half-open window
     * {@code [windowStart, windowEnd)} on {@code tweets.published_at}.
     *
     * <p>Ranking formula: number of qualifying tweet-hashtag relationships —
     * i.e. distinct tweets — per hashtag, descending. A hashtag used twice
     * inside one tweet scores 1 (the {@code usage_count} column counts
     * in-tweet occurrences and is deliberately not summed).
     *
     * <p>Lifecycle filters (applied in the query itself, mirroring the
     * existing read-query conventions): the tweet must not be soft-deleted,
     * the author must not be deleted, and retweet marker rows (which carry no
     * content and never carry hashtags) are excluded. Replies and quotes are
     * ordinary tweet rows and count when they otherwise qualify.
     *
     * <p>Deterministic ordering: {@code score DESC, tag ASC} — the tag is
     * unique, so the total order is stable across repeated calls.
     *
     * <p>{@code windowStart}/{@code windowEnd} are bound parameters computed
     * once by the caller, so this rows query and
     * {@link #countTrendingHashtags} always see the identical window.
     */
    @Query("""
            SELECT new logic_core.infrastructure.projection.TrendingHashtagProjection(
                th.hashtag.tag,
                COUNT(th)
            )
            FROM TweetHashtagEntity th
            JOIN th.tweet t
            JOIN t.author a
            WHERE t.isDeleted = false
              AND a.isDeleted = false
              AND t.retweetOf IS NULL
              AND t.publishedAt >= :windowStart
              AND t.publishedAt < :windowEnd
            GROUP BY th.hashtag.tag
            ORDER BY COUNT(th) DESC, th.hashtag.tag ASC
            """)
    List<TrendingHashtagProjection> findTrending(
            @Param("windowStart") OffsetDateTime windowStart,
            @Param("windowEnd") OffsetDateTime windowEnd,
            Pageable pageable
    );

    /**
     * Number of distinct hashtags having at least one qualifying relationship
     * inside the same window as {@link #findTrending} — feeds
     * {@code totalItems} so the returned rows and the count can never
     * disagree.
     */
    @Query("""
            SELECT COUNT(DISTINCT th.hashtag)
            FROM TweetHashtagEntity th
            JOIN th.tweet t
            JOIN t.author a
            WHERE t.isDeleted = false
              AND a.isDeleted = false
              AND t.retweetOf IS NULL
              AND t.publishedAt >= :windowStart
              AND t.publishedAt < :windowEnd
            """)
    long countTrendingHashtags(
            @Param("windowStart") OffsetDateTime windowStart,
            @Param("windowEnd") OffsetDateTime windowEnd
    );
}