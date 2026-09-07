package logic_core.infrastructure.mapper;

import logic_core.domain.model.TweetMention;
import logic_core.infrastructure.persistence.entity.UserEntity;
import logic_core.infrastructure.persistence.entity.mention.TweetMentionEntity;
import logic_core.infrastructure.persistence.entity.tweet.TweetEntity;

public final class MentionEntityMapper
{
    private MentionEntityMapper()
    {
    }

    public static TweetMention toDomain(TweetMentionEntity entity)
    {
        if (entity == null)
        {
            return null;
        }

        return TweetMention.create(
                entity.getMentionedUser().getId(),
                entity.getTweet().getId()
        );
    }

    public static TweetMentionEntity toPersistence(
            TweetMention mention,
            UserEntity mentionedUser,
            TweetEntity tweet)
    {
        if (mention == null)
        {
            return null;
        }

        TweetMentionEntity entity = new TweetMentionEntity();
        entity.setMentionedUser(mentionedUser);
        entity.setTweet(tweet);
        return entity;
    }
}