package logic_core.infrastructure.repository;

import logic_core.app.dto.timeline.TimelineTweet;
import logic_core.domain.model.TweetModel;
import logic_core.domain.repository.TimelineType;
import logic_core.domain.repository.TweetRepository;
import logic_core.infrastructure.mapper.TweetEntityMapper;
import logic_core.infrastructure.persistence.entity.tweet.TweetEntity;
import logic_core.infrastructure.projection.TimelineTweetProjection;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@Transactional
public class TweetRepositoryAdapter implements TweetRepository {

    private final TweetJpaRepository tweetJpaRepository;
    private final UserJpaRepository userJpaRepository;

    public TweetRepositoryAdapter(TweetJpaRepository tweetJpaRepository, UserJpaRepository userJpaRepository) {
        this.tweetJpaRepository = tweetJpaRepository;
        this.userJpaRepository = userJpaRepository;
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
            return tweetJpaRepository.findHomeTimeline(actorId, PageRequest.of(offset / limit, limit))
                    .stream()
                    .map(TweetRepositoryAdapter::toTimelineTweet)
                    .toList();
        }
        if (type == TimelineType.USER) {
            return tweetJpaRepository.findUserTimeline(actorId, targetUserId, PageRequest.of(offset / limit, limit))
                    .stream()
                    .map(TweetRepositoryAdapter::toTimelineTweet)
                    .toList();
        }
        if (type == TimelineType.FOLLOWING) {
            return tweetJpaRepository.findFollowingTimeline(actorId, PageRequest.of(offset / limit, limit))
                    .stream()
                    .map(TweetRepositoryAdapter::toTimelineTweet)
                    .toList();
        }
        if (type == TimelineType.REPLIES) {
            return tweetJpaRepository.findRepliesTimeline(actorId, targetUserId, PageRequest.of(offset / limit, limit))
                    .stream()
                    .map(TweetRepositoryAdapter::toTimelineTweet)
                    .toList();
        }
        if (type == TimelineType.MEDIA) {
            return tweetJpaRepository.findMediaTimeline(actorId, targetUserId, PageRequest.of(offset / limit, limit))
                    .stream()
                    .map(TweetRepositoryAdapter::toTimelineTweet)
                    .toList();
        }
        if (type == TimelineType.LIKED) {
            return tweetJpaRepository.findLikedTimeline(actorId, targetUserId, PageRequest.of(offset / limit, limit))
                    .stream()
                    .map(TweetRepositoryAdapter::toTimelineTweet)
                    .toList();
        }
        return List.of();
    }

    @Override
    public List<TimelineTweet> getRepliesOfTweet(UUID actorId, UUID tweetId) {
        return tweetJpaRepository
                .findRepliesOfTweet(actorId, tweetId)
                .stream()
                .map(TweetRepositoryAdapter::toTimelineTweet)
                .toList();
    }

    @Override
    public Optional<TimelineTweet> findSingleTweet(UUID actorId, UUID tweetId) {
        return tweetJpaRepository
                .findSingleTweetForActor(actorId, tweetId)
                .map(TweetRepositoryAdapter::toTimelineTweet);
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

    private static TimelineTweet toTimelineTweet(TimelineTweetProjection p) {
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
