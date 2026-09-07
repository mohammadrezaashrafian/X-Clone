package logic_core.infrastructure.mapper;

import logic_core.domain.model.BookmarkRelation;
import logic_core.infrastructure.persistence.entity.UserEntity;
import logic_core.infrastructure.persistence.entity.bookmark.BookmarkEntity;
import logic_core.infrastructure.persistence.entity.tweet.TweetEntity;

public final class BookmarkEntityMapper
{
    private BookmarkEntityMapper()
    {
    }

    public static BookmarkRelation toDomain(BookmarkEntity entity)
    {
        if (entity == null)
        {
            return null;
        }

        return BookmarkRelation.create(
                entity.getUser().getId(),
                entity.getTweet().getId()
        );
    }

    public static BookmarkEntity toPersistence(
            BookmarkRelation bookmark,
            UserEntity user,
            TweetEntity tweet)
    {
        if (bookmark == null)
        {
            return null;
        }

        BookmarkEntity entity = new BookmarkEntity();
        entity.setUser(user);
        entity.setTweet(tweet);
        entity.setCreatedAt(bookmark.getCreatedAt());
        return entity;
    }
}