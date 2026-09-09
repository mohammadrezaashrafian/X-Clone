package logic_core.domain.repository;

import logic_core.domain.model.TweetMention;

import java.util.List;
import java.util.UUID;

public interface MentionRepository
{
    /**
     * Persist one tweet/user mention relationship. Duplicate relationships
     * (same mentioned user, same tweet) are prevented by the database primary
     * key.
     */
    void attachToTweet(TweetMention mention);

    /**
     * Hard-deletes all mention relationships belonging to a tweet. Used during
     * tweet deletion to cascade-delete related mentions alongside likes, media
     * and edit history.
     */
    void deleteByTweetId(UUID tweetId);

    /**
     * The user IDs mentioned in a tweet, in no particular order.
     */
    List<UUID> findMentionedUserIdsByTweetId(UUID tweetId);
}