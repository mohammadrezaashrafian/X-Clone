package logic_core.infrastructure.mapper;

import logic_core.domain.model.HashtagModel;
import logic_core.infrastructure.persistence.entity.hashtag.HashtagEntity;

public final class HashtagEntityMapper
{
    private HashtagEntityMapper()
    {
    }

    public static HashtagModel toDomain(HashtagEntity entity)
    {
        if (entity == null)
        {
            return null;
        }

        return HashtagModel.restore(
                entity.getId(),
                entity.getTag(),
                entity.getCreatedAt()
        );
    }

    public static HashtagEntity toPersistence(HashtagModel model)
    {
        if (model == null)
        {
            return null;
        }

        HashtagEntity entity = new HashtagEntity();
        entity.setTag(model.getTag());
        return entity;
    }
}