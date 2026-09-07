package logic_core.infrastructure.mapper;

import logic_core.domain.model.TweetHashtag;
import logic_core.infrastructure.persistence.entity.hashtag.HashtagEntity;
import logic_core.infrastructure.persistence.entity.hashtag.TweetHashtagEntity;
import logic_core.infrastructure.persistence.entity.tweet.TweetEntity;

public final class TweetHashtagEntityMapper
{
    private TweetHashtagEntityMapper()
    {
    }

    public static TweetHashtagEntity toPersistence(
            TweetHashtag relation,
            HashtagEntity hashtag,
            TweetEntity tweet)
    {
        if (relation == null)
        {
            return null;
        }

        TweetHashtagEntity entity = new TweetHashtagEntity();
        entity.setHashtag(hashtag);
        entity.setTweet(tweet);
        entity.setUsageCount(relation.getUsageCount());
        return entity;
    }
}