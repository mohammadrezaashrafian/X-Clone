package logic_core.infrastructure.repository;

import logic_core.infrastructure.persistence.entity.poll.PollEntity;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PollJpaRepository extends JpaRepository<PollEntity, UUID>
{
    @EntityGraph(attributePaths = "options")
    Optional<PollEntity> findByTweet_Id(UUID tweetId);

    @EntityGraph(attributePaths = "options")
    @Query("SELECT p FROM PollEntity p WHERE p.tweet.id IN :tweetIds")
    List<PollEntity> findByTweetIdsWithOptions(@Param("tweetIds") Collection<UUID> tweetIds);

    @EntityGraph(attributePaths = "options")
    @Override
    Optional<PollEntity> findById(UUID id);

    /**
     * Hard-deletes a tweet's poll (options and votes removed via FK CASCADE).
     * Used during tweet deletion to cascade-delete poll data alongside likes,
     * media, mentions and bookmarks.
     */
    @Modifying
    @Query("DELETE FROM PollEntity p WHERE p.tweet.id = :tweetId")
    void deleteByTweetId(@Param("tweetId") UUID tweetId);
}