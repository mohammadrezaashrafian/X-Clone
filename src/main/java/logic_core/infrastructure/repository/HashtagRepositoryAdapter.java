package logic_core.infrastructure.repository;

import logic_core.app.dto.response.PollResponse;
import logic_core.app.dto.timeline.TimelineMedia;
import logic_core.app.dto.timeline.TimelineTweet;
import logic_core.app.mapper.PollMapper;
import logic_core.domain.model.HashtagFollow;
import logic_core.domain.model.HashtagModel;
import logic_core.domain.model.MediaModel;
import logic_core.domain.model.TrendingHashtag;
import logic_core.domain.model.TweetHashtag;
import logic_core.domain.repository.HashtagRepository;
import logic_core.domain.repository.MediaRepository;
import logic_core.domain.repository.PollRepository;
import logic_core.infrastructure.mapper.HashtagEntityMapper;
import logic_core.infrastructure.mapper.HashtagFollowEntityMapper;
import logic_core.infrastructure.mapper.TweetHashtagEntityMapper;
import logic_core.infrastructure.persistence.entity.hashtag.HashtagEntity;
import logic_core.infrastructure.persistence.entity.hashtag.HashtagFollowEntity;
import logic_core.infrastructure.persistence.entity.hashtag.HashtagFollowEntityId;
import logic_core.infrastructure.persistence.entity.hashtag.TweetHashtagEntity;
import logic_core.infrastructure.projection.TimelineTweetProjection;
import logic_core.infrastructure.projection.TrendingHashtagProjection;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
@Transactional
public class HashtagRepositoryAdapter implements HashtagRepository
{
    private final HashtagJpaRepository hashtagJpaRepository;
    private final HashtagFollowJpaRepository hashtagFollowJpaRepository;
    private final TweetHashtagJpaRepository tweetHashtagJpaRepository;
    private final UserJpaRepository userJpaRepository;
    private final TweetJpaRepository tweetJpaRepository;
    private final PollRepository pollRepository;
    private final MediaRepository mediaRepository;

    public HashtagRepositoryAdapter(
            HashtagJpaRepository hashtagJpaRepository,
            HashtagFollowJpaRepository hashtagFollowJpaRepository,
            TweetHashtagJpaRepository tweetHashtagJpaRepository,
            UserJpaRepository userJpaRepository,
            TweetJpaRepository tweetJpaRepository,
            PollRepository pollRepository,
            MediaRepository mediaRepository)
    {
        this.hashtagJpaRepository = hashtagJpaRepository;
        this.hashtagFollowJpaRepository = hashtagFollowJpaRepository;
        this.tweetHashtagJpaRepository = tweetHashtagJpaRepository;
        this.userJpaRepository = userJpaRepository;
        this.tweetJpaRepository = tweetJpaRepository;
        this.pollRepository = pollRepository;
        this.mediaRepository = mediaRepository;
    }

    @Override
    public Optional<HashtagModel> findById(UUID hashtagId)
    {
        return hashtagJpaRepository.findById(hashtagId)
                .map(HashtagEntityMapper::toDomain);
    }

    @Override
    public Optional<HashtagModel> findByTag(String tag)
    {
        return hashtagJpaRepository.findByTag(tag)
                .map(HashtagEntityMapper::toDomain);
    }

    @Override
    public List<HashtagModel> findByTags(Collection<String> tags)
    {
        if (tags == null || tags.isEmpty())
        {
            return List.of();
        }

        return hashtagJpaRepository.findByTagIn(tags).stream()
                .map(HashtagEntityMapper::toDomain)
                .toList();
    }

    @Override
    public HashtagModel save(HashtagModel hashtag)
    {
        // saveAndFlush so the @CreationTimestamp generated value is populated
        // on the entity before it is mapped back to the domain model.
        HashtagEntity saved = hashtagJpaRepository.saveAndFlush(
                HashtagEntityMapper.toPersistence(hashtag)
        );
        return HashtagEntityMapper.toDomain(saved);
    }

    @Override
    public void attachToTweet(TweetHashtag relation)
    {
        TweetHashtagEntity entity = TweetHashtagEntityMapper.toPersistence(
                relation,
                hashtagJpaRepository.getReferenceById(relation.getHashtagId()),
                tweetJpaRepository.getReferenceById(relation.getTweetId())
        );
        tweetHashtagJpaRepository.save(entity);
    }

    @Override
    public boolean isFollowing(UUID userId, UUID hashtagId)
    {
        return hashtagFollowJpaRepository.existsByHashtag_IdAndUser_Id(hashtagId, userId);
    }

    @Override
    public Optional<HashtagFollow> findFollow(UUID userId, UUID hashtagId)
    {
        return hashtagFollowJpaRepository
                .findByHashtag_IdAndUser_Id(hashtagId, userId)
                .map(HashtagFollowEntityMapper::toDomain);
    }

    @Override
    public void saveFollow(HashtagFollow follow)
    {
        HashtagFollowEntity entity = HashtagFollowEntityMapper.toPersistence(
                follow,
                hashtagJpaRepository.getReferenceById(follow.getHashtagId()),
                userJpaRepository.getReferenceById(follow.getUserId())
        );
        hashtagFollowJpaRepository.save(entity);
    }

    @Override
    public void deleteFollow(HashtagFollow follow)
    {
        HashtagFollowEntityId id = new HashtagFollowEntityId(
                follow.getHashtagId(),
                follow.getUserId()
        );
        hashtagFollowJpaRepository.deleteById(id);
    }

    @Override
    public long countFollowers(UUID hashtagId)
    {
        return hashtagFollowJpaRepository.countByHashtag_Id(hashtagId);
    }

    @Override
    public List<TimelineTweet> getTweetsByHashtag(
            UUID actorId,
            UUID hashtagId,
            int limit,
            int offset)
    {
        int page = limit > 0 ? offset / limit : 0;

        return toTimelineTweets(
                tweetHashtagJpaRepository.findTweetsByHashtag(
                        actorId,
                        hashtagId,
                        PageRequest.of(page, limit)
                )
        );
    }

    @Override
    public long countTweetsByHashtag(UUID actorId, UUID hashtagId)
    {
        return tweetHashtagJpaRepository.countTweetsByHashtag(actorId, hashtagId);
    }

    @Override
    public List<HashtagModel> searchByTagPrefix(String prefix, int limit, int offset)
    {
        if (prefix == null || prefix.isEmpty())
        {
            return List.of();
        }

        return hashtagJpaRepository.searchByTagPrefix(
                        toPrefixLikePattern(prefix),
                        PageRequest.of(offset / limit, limit)
                ).stream()
                .map(HashtagEntityMapper::toDomain)
                .toList();
    }

    @Override
    public long countByTagPrefix(String prefix)
    {
        if (prefix == null || prefix.isEmpty())
        {
            return 0L;
        }
        return hashtagJpaRepository.countByTagPrefix(toPrefixLikePattern(prefix));
    }

    /**
     * Builds the LIKE prefix pattern for hashtag search, hardening the
     * canonical prefix for LIKE semantics: backslash, percent and underscore
     * are escaped with a backslash, matching the ESCAPE '\\' clause in the
     * search JPQL. The prefix is already lowercase (canonical form produced by
     * the use case); it is lowercased again defensively. Bound as a parameter.
     */
    private static String toPrefixLikePattern(String prefix)
    {
        String escaped = prefix
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");

        return escaped.toLowerCase(Locale.ROOT) + "%";
    }

    @Override
    public List<TrendingHashtag> findTrending(
            OffsetDateTime windowStart,
            OffsetDateTime windowEnd,
            int limit)
    {
        return hashtagJpaRepository.findTrending(windowStart, windowEnd, PageRequest.of(0, limit))
                .stream()
                .map(projection -> new TrendingHashtag(
                        projection.tag(),
                        projection.score()))
                .toList();
    }

    @Override
    public long countTrendingHashtags(OffsetDateTime windowStart, OffsetDateTime windowEnd)
    {
        return hashtagJpaRepository.countTrendingHashtags(windowStart, windowEnd);
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