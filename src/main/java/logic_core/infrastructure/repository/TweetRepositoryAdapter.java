package logic_core.infrastructure.repository;

import logic_core.app.dto.response.PollResponse;
import logic_core.app.dto.timeline.TimelineMedia;
import logic_core.app.dto.timeline.TimelineTweet;
import logic_core.app.mapper.PollMapper;
import logic_core.domain.model.MediaModel;
import logic_core.domain.model.TweetModel;
import logic_core.domain.repository.MediaRepository;
import logic_core.domain.repository.PollRepository;
import logic_core.domain.repository.TimelineType;
import logic_core.domain.repository.TweetRepository;
import logic_core.infrastructure.mapper.TweetEntityMapper;
import logic_core.infrastructure.persistence.entity.tweet.TweetEntity;
import logic_core.infrastructure.projection.TimelineTweetProjection;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@Transactional
public class TweetRepositoryAdapter implements TweetRepository {

    private final TweetJpaRepository tweetJpaRepository;
    private final UserJpaRepository userJpaRepository;
    private final PollRepository pollRepository;
    private final MediaRepository mediaRepository;

    public TweetRepositoryAdapter(
            TweetJpaRepository tweetJpaRepository,
            UserJpaRepository userJpaRepository,
            PollRepository pollRepository,
            MediaRepository mediaRepository) {
        this.tweetJpaRepository = tweetJpaRepository;
        this.userJpaRepository = userJpaRepository;
        this.pollRepository = pollRepository;
        this.mediaRepository = mediaRepository;
    }

    @Override
    public Optional<TweetModel> save(TweetModel model) {
        TweetEntity entity;
        if (model.getId() != null) {
            entity = tweetJpaRepository.findById(model.getId()).orElse(new TweetEntity());
        } else {
            entity = new TweetEntity();
        }
        updateEntityWithRelations(entity, model);
        return Optional.of(TweetEntityMapper.toModel(tweetJpaRepository.save(entity)));
    }

    @Override
    public void update(TweetModel model) {
        tweetJpaRepository.findById(model.getId()).ifPresent(entity -> {
            updateEntityWithRelations(entity, model);
            tweetJpaRepository.save(entity);
        });
    }

    @Override
    public void softDelete(UUID tweetId) {
        tweetJpaRepository.findByIdAndIsDeletedFalse(tweetId).ifPresent(entity -> {
            entity.markDeleted();
            entity.setContent("This post has been deleted.");
            entity.setScheduledAt(null);
            entity.setPinned(false);
            tweetJpaRepository.save(entity);
        });
    }

    @Override
    public Optional<TweetModel> findById(UUID tweetId) {
        return tweetJpaRepository.findById(tweetId).map(TweetEntityMapper::toModel);
    }

    @Override
    public Optional<TweetModel> findActiveById(UUID tweetId) {
        return tweetJpaRepository.findByIdAndIsDeletedFalse(tweetId).map(TweetEntityMapper::toModel);
    }

    @Override
    public List<TweetModel> findByAuthorId(UUID authorId) {
        return tweetJpaRepository
                .findByAuthorIdAndIsDeletedFalseOrderByCreatedAtDesc(authorId)
                .stream()
                .map(TweetEntityMapper::toModel)
                .toList();
    }

    @Override
    public List<TweetModel> findActiveByAuthorId(UUID authorId) {
        return tweetJpaRepository
                .findByAuthorIdAndIsDeletedFalseOrderByCreatedAtDesc(authorId)
                .stream()
                .map(TweetEntityMapper::toModel)
                .toList();
    }

    @Override
    public List<TweetModel> findRepliesByTweetId(UUID tweetId) {
        return tweetJpaRepository
                .findByReplyToIdAndIsDeletedFalseOrderByCreatedAtAsc(tweetId)
                .stream()
                .map(TweetEntityMapper::toModel)
                .toList();
    }

    @Override
    public List<TweetModel> findRetweetsOfTweet(UUID tweetId) {
        return tweetJpaRepository
                .findByRetweetOfIdAndIsDeletedFalseOrderByCreatedAtDesc(tweetId)
                .stream()
                .map(TweetEntityMapper::toModel)
                .toList();
    }

    @Override
    public List<TweetModel> findQuotesOfTweet(UUID tweetId) {
        return tweetJpaRepository.findByQuoteOfIdAndIsDeletedFalseOrderByCreatedAtDesc(tweetId).stream().map(TweetEntityMapper::toModel).collect(Collectors.toList());
    }

    // LEGACY: Timeline/Interactions
    @Override
    public List<TweetModel> findTweetsRepliedByUser(UUID userId) {
        return tweetJpaRepository
                .findTweetsRepliedByUser(userId)
                .stream()
                .map(TweetEntityMapper::toModel)
                .toList();
    }

    @Override
    public List<TweetModel> findTweetsRetweetedByUser(UUID userId) {
        return tweetJpaRepository
                .findTweetsRetweetedByUser(userId)
                .stream()
                .map(TweetEntityMapper::toModel)
                .toList();
    }

    @Override
    public List<TweetModel> findTimelineTweets(UUID userId) {
        return tweetJpaRepository
                .findTimelineTweets(userId)
                .stream()
                .map(TweetEntityMapper::toModel)
                .toList();
    }

    @Override
    public boolean isRepliedByUser(UUID tweetId, UUID userId) {
        return tweetJpaRepository.isRepliedByUser(tweetId, userId);
    }

    @Override
    public boolean isRetweetedByUser(UUID tweetId, UUID userId) {
        return tweetJpaRepository.isRetweetedByUser(tweetId, userId);
    }

    @Override
    public int deleteActiveRetweetByUser(UUID tweetId, UUID userId) {
        return tweetJpaRepository.deleteActiveRetweetByUser(tweetId, userId);
    }

    @Override
    public long countRepliesByTweetId(UUID tweetId) {
        return tweetJpaRepository.countByReplyToIdAndIsDeletedFalse(tweetId);
    }

    @Override
    public long countRetweetsByTweetId(UUID tweetId) {
        return tweetJpaRepository.countByRetweetOfIdAndIsDeletedFalse(tweetId);
    }
    @Override
    public List<TimelineTweet> getTimeline(TimelineType type, UUID actorId, UUID targetUserId, int limit, int offset) {
        if (type == TimelineType.HOME) {
            return toTimelineTweets(tweetJpaRepository
                    .findHomeTimeline(actorId, PageRequest.of(offset / limit, limit)));
        }
        if (type == TimelineType.USER) {
            return toTimelineTweets(tweetJpaRepository
                    .findUserTimeline(actorId, targetUserId, PageRequest.of(offset / limit, limit)));
        }
        if (type == TimelineType.FOLLOWING) {
            return toTimelineTweets(tweetJpaRepository
                    .findFollowingTimeline(actorId, PageRequest.of(offset / limit, limit)));
        }
        if (type == TimelineType.REPLIES) {
            return toTimelineTweets(tweetJpaRepository
                    .findRepliesTimeline(actorId, targetUserId, PageRequest.of(offset / limit, limit)));
        }
        if (type == TimelineType.MEDIA) {
            return toTimelineTweets(tweetJpaRepository
                    .findMediaTimeline(actorId, targetUserId, PageRequest.of(offset / limit, limit)));
        }
        if (type == TimelineType.LIKED) {
            return toTimelineTweets(tweetJpaRepository
                    .findLikedTimeline(actorId, targetUserId, PageRequest.of(offset / limit, limit)));
        }
        return List.of();
    }

    @Override
    public List<TimelineTweet> getRepliesOfTweet(UUID actorId, UUID tweetId) {
        return toTimelineTweets(
                tweetJpaRepository.findRepliesOfTweet(actorId, tweetId)
        );
    }

    @Override
    public Optional<TimelineTweet> findSingleTweet(UUID actorId, UUID tweetId) {
        return tweetJpaRepository
                .findSingleTweetForActor(actorId, tweetId)
                .map(projection -> {
                    PollResponse poll = pollRepository.findByTweetId(tweetId)
                            .map(PollMapper::toResponse)
                            .orElse(null);
                    List<TimelineMedia> media = loadMediaByTweet(List.of(tweetId))
                            .getOrDefault(tweetId, List.of());
                    return toTimelineTweet(projection, poll, media);
                });
    }

    @Override
    public List<TimelineTweet> searchTweets(UUID actorId, String term, int limit, int offset) {
        return toTimelineTweets(
                tweetJpaRepository.searchTweetsForActor(
                        actorId,
                        toContentLikePattern(term),
                        PageRequest.of(offset / limit, limit)
                )
        );
    }

    @Override
    public long countSearchTweets(UUID actorId, String term) {
        return tweetJpaRepository.countSearchTweetsForActor(
                actorId,
                toContentLikePattern(term)
        );
    }

    /**
     * Builds the lowercase LIKE pattern for tweet-content search, hardening
     * the raw term for LIKE semantics: backslash, percent and underscore are
     * escaped with a backslash, matching the ESCAPE '\\' clause in the search
     * JPQL. The whole term is lowercased to pair with LOWER(t.content) in the
     * query; the pattern is bound as a parameter (no string concatenation into
     * the query text).
     */
    private static String toContentLikePattern(String term)
    {
        String escaped = term
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");

        return "%" + escaped.toLowerCase(Locale.ROOT) + "%";
    }

    @Override
    public long countTimeline(TimelineType type, UUID actorId, UUID targetUserId) {
        if (type == TimelineType.HOME) {
            return tweetJpaRepository.countHomeTimeline(actorId);
        }
        if (type == TimelineType.USER) {
            return tweetJpaRepository.countUserTimeline(actorId, targetUserId);
        }
        if (type == TimelineType.FOLLOWING) {
            return tweetJpaRepository.countFollowingTimeline(actorId);
        }
        if (type == TimelineType.REPLIES) {
            return tweetJpaRepository.countRepliesTimeline(actorId, targetUserId);
        }
        if (type == TimelineType.MEDIA) {
            return tweetJpaRepository.countMediaTimeline(actorId, targetUserId);
        }
        if (type == TimelineType.LIKED) {
            return tweetJpaRepository.countLikedTimeline(actorId, targetUserId);
        }
        return 0L;
    }

    private List<TimelineTweet> toTimelineTweets(List<TimelineTweetProjection> projections) {
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
    private Map<UUID, List<TimelineMedia>> loadMediaByTweet(List<UUID> tweetIds) {
        Map<UUID, List<TimelineMedia>> mediaByTweet = new java.util.HashMap<>();

        for (MediaModel media : mediaRepository.findByTweetIds(tweetIds))
        {
            mediaByTweet
                    .computeIfAbsent(media.getTweetId(), id -> new java.util.ArrayList<>())
                    .add(toTimelineMedia(media));
        }

        return mediaByTweet;
    }

    private static TimelineMedia toTimelineMedia(MediaModel media) {
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
            List<TimelineMedia> media) {
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
    @Override
    public Optional<TweetModel> findActiveByIdForUpdate(UUID tweetId) {
        return tweetJpaRepository
                .findActiveByIdForUpdate(tweetId)
                .map(TweetEntityMapper::toModel);
    }
    @Override
    public boolean existsById(UUID tweetId) {
        return tweetJpaRepository.existsByIdAndIsDeletedFalse(tweetId);
    }

    @Override
    public boolean existsActiveById(UUID tweetId) { return tweetJpaRepository.existsByIdAndIsDeletedFalse(tweetId); }

    @Override
    public List<TweetModel> findTweetsByAuthorId(UUID authorId) { return findActiveByAuthorId(authorId); }
    @Override
    public long countTweetsById(UUID authorId) { return tweetJpaRepository.countByAuthorIdAndIsDeletedFalse(authorId); }

    private void updateEntityWithRelations(TweetEntity entity, TweetModel model) {
        TweetEntityMapper.updateEntity(entity, model);
        if (model.getAuthorId() != null) entity.setAuthor(userJpaRepository.getReferenceById(model.getAuthorId()));
        if (model.getRepliedToTweetId() != null) entity.setReplyTo(tweetJpaRepository.getReferenceById(model.getRepliedToTweetId()));
        if (model.getRetweetedTweetId() != null) entity.setRetweetOf(tweetJpaRepository.getReferenceById(model.getRetweetedTweetId()));
        if (model.getQuotedTweetId() != null) entity.setQuoteOf(tweetJpaRepository.getReferenceById(model.getQuotedTweetId()));
    }
}
