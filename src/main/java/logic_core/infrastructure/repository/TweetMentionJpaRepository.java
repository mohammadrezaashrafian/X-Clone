package logic_core.infrastructure.repository;

import logic_core.infrastructure.persistence.entity.mention.TweetMentionEntity;
import logic_core.infrastructure.persistence.entity.mention.TweetMentionEntityId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TweetMentionJpaRepository
        extends JpaRepository<TweetMentionEntity, TweetMentionEntityId>
{
    List<TweetMentionEntity> findByTweet_Id(UUID tweetId);

    /**
     * Hard-deletes all mention records belonging to a tweet. Used during tweet
     * deletion to cascade-delete related mentions alongside likes, media and
     * edit history.
     */
    @Modifying
    @Query("DELETE FROM TweetMentionEntity tm WHERE tm.tweet.id = :tweetId")
    void deleteByTweetId(@Param("tweetId") UUID tweetId);
}