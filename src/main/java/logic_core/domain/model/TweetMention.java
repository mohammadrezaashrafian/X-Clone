package logic_core.domain.model;

import lombok.Getter;

import java.util.Objects;
import java.util.UUID;

/**
 * Domain representation of a mention relationship between a tweet and one of
 * the users it mentions. Persistence lives in the infrastructure layer; this
 * model carries no JPA annotations.
 */
@Getter
public class TweetMention
{
    private final UUID mentionedUserId;
    private final UUID tweetId;

    private TweetMention(UUID mentionedUserId, UUID tweetId)
    {
        this.mentionedUserId = Objects.requireNonNull(
                mentionedUserId,
                "TweetMention.mentionedUserId cannot be null");
        this.tweetId = Objects.requireNonNull(
                tweetId,
                "TweetMention.tweetId cannot be null");
    }

    public static TweetMention create(UUID mentionedUserId, UUID tweetId)
    {
        return new TweetMention(mentionedUserId, tweetId);
    }
}