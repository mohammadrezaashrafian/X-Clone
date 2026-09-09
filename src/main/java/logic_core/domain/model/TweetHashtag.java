package logic_core.domain.model;

import lombok.Getter;

import java.util.Objects;
import java.util.UUID;

/**
 * Domain representation of the relationship between a tweet and one of its
 * hashtags, including how many times the tag occurred inside the tweet
 * content ({@code usage_count}).
 */
@Getter
public class TweetHashtag
{
    private final UUID hashtagId;
    private final UUID tweetId;
    private final int usageCount;

    private TweetHashtag(UUID hashtagId, UUID tweetId, int usageCount)
    {
        this.hashtagId = Objects.requireNonNull(hashtagId, "TweetHashtag.hashtagId cannot be null");
        this.tweetId = Objects.requireNonNull(tweetId, "TweetHashtag.tweetId cannot be null");

        if (usageCount < 1)
        {
            throw new IllegalArgumentException("TweetHashtag.usageCount must be positive");
        }

        this.usageCount = usageCount;
    }

    public static TweetHashtag create(UUID hashtagId, UUID tweetId, int usageCount)
    {
        return new TweetHashtag(hashtagId, tweetId, usageCount);
    }
}