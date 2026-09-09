package logic_core.infrastructure.repository;

import logic_core.infrastructure.persistence.entity.hashtag.HashtagEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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
}