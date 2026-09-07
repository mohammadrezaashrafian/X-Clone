package logic_core.infrastructure.repository;

import logic_core.app.dto.timeline.TimelineTweet;
import logic_core.domain.model.BookmarkRelation;
import logic_core.domain.repository.BookmarkRepository;
import logic_core.infrastructure.mapper.BookmarkEntityMapper;
import logic_core.infrastructure.persistence.entity.bookmark.BookmarkEntity;
import logic_core.infrastructure.persistence.entity.bookmark.BookmarkEntityId;
import logic_core.infrastructure.projection.TimelineTweetProjection;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@Transactional
public class BookmarkRepositoryAdapter implements BookmarkRepository
{
    private final BookmarkJpaRepository bookmarkJpaRepository;
    private final UserJpaRepository userJpaRepository;
    private final TweetJpaRepository tweetJpaRepository;

    public BookmarkRepositoryAdapter(
            BookmarkJpaRepository bookmarkJpaRepository,
            UserJpaRepository userJpaRepository,
            TweetJpaRepository tweetJpaRepository)
    {
        this.bookmarkJpaRepository = bookmarkJpaRepository;
        this.userJpaRepository = userJpaRepository;
        this.tweetJpaRepository = tweetJpaRepository;
    }

    @Override
    public boolean isBookmarked(UUID userId, UUID tweetId)
    {
        return bookmarkJpaRepository.existsByUser_IdAndTweet_Id(userId, tweetId);
    }

    @Override
    public Optional<BookmarkRelation> findBookmark(UUID userId, UUID tweetId)
    {
        return bookmarkJpaRepository.findByUser_IdAndTweet_Id(userId, tweetId)
                .map(BookmarkEntityMapper::toDomain);
    }

    @Override
    public void saveBookmark(BookmarkRelation bookmark)
    {
        BookmarkEntity entity = BookmarkEntityMapper.toPersistence(
                bookmark,
                userJpaRepository.getReferenceById(bookmark.getUserId()),
                tweetJpaRepository.getReferenceById(bookmark.getTweetId())
        );
        bookmarkJpaRepository.save(entity);
    }

    @Override
    public void deleteBookmark(BookmarkRelation bookmark)
    {
        BookmarkEntityId id = new BookmarkEntityId(
                bookmark.getUserId(),
                bookmark.getTweetId()
        );
        bookmarkJpaRepository.deleteById(id);
    }

    @Override
    public void deleteByTweetId(UUID tweetId)
    {
        bookmarkJpaRepository.deleteByTweetId(tweetId);
    }

    @Override
    public List<TimelineTweet> getBookmarks(UUID userId, int limit, int offset)
    {
        int page = limit > 0 ? offset / limit : 0;

        return bookmarkJpaRepository
                .findBookmarksForUser(userId, PageRequest.of(page, limit))
                .stream()
                .map(BookmarkRepositoryAdapter::toTimelineTweet)
                .toList();
    }

    @Override
    public long countBookmarks(UUID userId)
    {
        return bookmarkJpaRepository.countBookmarksForUser(userId);
    }

    private static TimelineTweet toTimelineTweet(TimelineTweetProjection p)
    {
        return TimelineTweet.builder()
                .tweetId(p.tweetId())
                .authorId(p.authorId())
                .username(p.username())
                .displayName(p.displayName())
                .avatarUrl(p.avatarUrl())
                .content(p.content())
                .likeCount(p.likeCount())
                .replyCount(p.replyCount())
                .retweetCount(p.retweetCount())
                .isLiked(false)
                .publishedAt(p.publishedAt())
                .media(List.of())
                .build();
    }
}