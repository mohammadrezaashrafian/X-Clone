package logic_core.infrastructure.repository;

import logic_core.app.dto.response.PollResponse;
import logic_core.app.dto.timeline.TimelineMedia;
import logic_core.app.dto.timeline.TimelineTweet;
import logic_core.app.mapper.PollMapper;
import logic_core.domain.model.BookmarkRelation;
import logic_core.domain.model.MediaModel;
import logic_core.domain.repository.BookmarkRepository;
import logic_core.domain.repository.MediaRepository;
import logic_core.domain.repository.PollRepository;
import logic_core.infrastructure.mapper.BookmarkEntityMapper;
import logic_core.infrastructure.persistence.entity.bookmark.BookmarkEntity;
import logic_core.infrastructure.persistence.entity.bookmark.BookmarkEntityId;
import logic_core.infrastructure.projection.TimelineTweetProjection;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
@Transactional
public class BookmarkRepositoryAdapter implements BookmarkRepository
{
    private final BookmarkJpaRepository bookmarkJpaRepository;
    private final UserJpaRepository userJpaRepository;
    private final TweetJpaRepository tweetJpaRepository;
    private final PollRepository pollRepository;
    private final MediaRepository mediaRepository;

    public BookmarkRepositoryAdapter(
            BookmarkJpaRepository bookmarkJpaRepository,
            UserJpaRepository userJpaRepository,
            TweetJpaRepository tweetJpaRepository,
            PollRepository pollRepository,
            MediaRepository mediaRepository)
    {
        this.bookmarkJpaRepository = bookmarkJpaRepository;
        this.userJpaRepository = userJpaRepository;
        this.tweetJpaRepository = tweetJpaRepository;
        this.pollRepository = pollRepository;
        this.mediaRepository = mediaRepository;
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

        return toTimelineTweets(
                bookmarkJpaRepository.findBookmarksForUser(
                        userId,
                        PageRequest.of(page, limit)
                )
        );
    }

    @Override
    public long countBookmarks(UUID userId)
    {
        return bookmarkJpaRepository.countBookmarksForUser(userId);
    }

    private List<TimelineTweet> toTimelineTweets(List<TimelineTweetProjection> projections)
    {
        if (projections == null || projections.isEmpty())
        {
            return List.of();
        }

        List<UUID> tweetIds = projections.stream()
                .map(TimelineTweetProjection::tweetId)
                .toList();

        Map<UUID, PollResponse> pollsByTweet =
                PollMapper.toResponsesByTweet(
                        pollRepository.findByTweetIds(tweetIds)
                );

        Map<UUID, List<TimelineMedia>> mediaByTweet =
                loadMediaByTweet(tweetIds);

        return projections.stream()
                .map(p -> toTimelineTweet(
                        p,
                        pollsByTweet.get(p.tweetId()),
                        mediaByTweet.getOrDefault(p.tweetId(), List.of())
                ))
                .toList();
    }

    /**
     * Bulk-loads attached media for the projected tweets in a single query
     * (no N+1) and groups it per tweet in display order.
     */
    private Map<UUID, List<TimelineMedia>> loadMediaByTweet(List<UUID> tweetIds)
    {
        Map<UUID, List<TimelineMedia>> mediaByTweet = new java.util.HashMap<>();

        for (MediaModel media : mediaRepository.findByTweetIds(tweetIds))
        {
            mediaByTweet
                    .computeIfAbsent(media.getTweetId(), id -> new java.util.ArrayList<>())
                    .add(toTimelineMedia(media));
        }

        return mediaByTweet;
    }

    private static TimelineMedia toTimelineMedia(MediaModel media)
    {
        return TimelineMedia.builder()
                .mediaId(media.getMediaId())
                .mediaUrl(media.getMediaUrl())
                .mediaType(media.getMediaType())
                .displayOrder(media.getDisplayOrder())
                .build();
    }

    private static TimelineTweet toTimelineTweet(
            TimelineTweetProjection p,
            PollResponse poll,
            List<TimelineMedia> media)
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
                .media(media)
                .poll(poll)
                .build();
    }
}