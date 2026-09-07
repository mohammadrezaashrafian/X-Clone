package logic_core.infrastructure.mapper;

import logic_core.domain.model.HashtagFollow;
import logic_core.infrastructure.persistence.entity.UserEntity;
import logic_core.infrastructure.persistence.entity.hashtag.HashtagEntity;
import logic_core.infrastructure.persistence.entity.hashtag.HashtagFollowEntity;

public final class HashtagFollowEntityMapper
{
    private HashtagFollowEntityMapper()
    {
    }

    public static HashtagFollow toDomain(HashtagFollowEntity entity)
    {
        if (entity == null)
        {
            return null;
        }

        return HashtagFollow.create(
                entity.getUser().getId(),
                entity.getHashtag().getId(),
                entity.getCreatedAt()
        );
    }

    public static HashtagFollowEntity toPersistence(
            HashtagFollow follow,
            HashtagEntity hashtag,
            UserEntity user)
    {
        if (follow == null)
        {
            return null;
        }

        HashtagFollowEntity entity = new HashtagFollowEntity();
        entity.setHashtag(hashtag);
        entity.setUser(user);
        entity.setCreatedAt(follow.getCreatedAt());
        return entity;
    }
}