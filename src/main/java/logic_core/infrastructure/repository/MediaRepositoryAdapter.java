package logic_core.infrastructure.repository;

import logic_core.domain.model.MediaModel;
import logic_core.domain.repository.MediaRepository;
import logic_core.infrastructure.mapper.MediaEntityMapper;
import logic_core.infrastructure.persistence.entity.UserEntity;
import logic_core.infrastructure.persistence.entity.media.MediaEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Infrastructure-level adapter for Media persistence operations.
 * <p>
 * Uses {@link MediaJpaRepository} and {@link TweetJpaRepository} for
 * Spring Data JPA operations, and {@link MediaEntityMapper} for
 * entity/domain mapping.
 * <p>
 * This adapter replaces the legacy {@code JpaMediaRepository} which
 * depended on {@code MediaDao} and had stub methods.
 */
@Component
@Transactional
public class MediaRepositoryAdapter implements MediaRepository {

    private final MediaJpaRepository mediaJpaRepository;
    private final TweetJpaRepository tweetJpaRepository;
    private final UserJpaRepository userJpaRepository;

    public MediaRepositoryAdapter(MediaJpaRepository mediaJpaRepository,
                                   TweetJpaRepository tweetJpaRepository,
                                   UserJpaRepository userJpaRepository) {
        this.mediaJpaRepository = mediaJpaRepository;
        this.tweetJpaRepository = tweetJpaRepository;
        this.userJpaRepository = userJpaRepository;
    }

    /**
     * Finds a media by its ID.
     *
     * @param mediaId the media's ID
     * @return the media model if found
     */
    @Override
    public Optional<MediaModel> findById(UUID mediaId) {
        return mediaJpaRepository.findById(mediaId)
                .map(MediaEntityMapper::toDomain);
    }

    /**
     * Finds multiple media by their IDs.
     *
     * @param mediaIds list of media IDs
     * @return list of media models, ordered by displayOrder
     */
    @Override
    public List<MediaModel> findByIds(List<UUID> mediaIds) {
        if (mediaIds == null || mediaIds.isEmpty()) {
            return List.of();
        }
        return mediaJpaRepository.findByIds(mediaIds)
                .stream()
                .map(MediaEntityMapper::toDomain)
                .toList();
    }

    /**
     * Checks if a media exists by its ID.
     *
     * @param mediaId the media's ID
     * @return true if the media exists
     */
    @Override
    public boolean existsById(UUID mediaId) {
        return mediaJpaRepository.existsById(mediaId);
    }

    /**
     * Checks if media belongs to a user.
     * 
     * NOTE: The legacy implementation referenced a non-existent Upload entity.
     * This method preserves the same behavior — it checks if media has no tweet.
     *
     * @param mediaId the media's ID
     * @param userId the user's ID
     * @return true if the media belongs to the user
     */
    @Override
    public boolean belongsToUser(UUID mediaId, UUID userId) {
        return mediaJpaRepository.belongsToUser(mediaId, userId);
    }

    /**
     * Checks if media is already attached to a tweet.
     *
     * @param mediaId the media's ID
     * @return true if the media is attached to a tweet
     */
    @Override
    public boolean isAlreadyAttached(UUID mediaId) {
        return mediaJpaRepository.isAlreadyAttached(mediaId);
    }

    /**
     * Counts media uploaded by a user that are not yet attached to a tweet.
     *
     * @param userId the owning user's ID
     * @return the number of unattached media records uploaded by the user
     */
    @Override
    public long countUnattachedByUser(UUID userId) {
        return mediaJpaRepository.countUnattachedByUser(userId);
    }

    /**
     * Loads media attached to the given tweets, ordered by tweet and displayOrder.
     *
     * @param tweetIds the tweet IDs whose media should be loaded
     * @return media models grouped per tweet in display order
     */
    @Override
    public List<MediaModel> findByTweetIds(Collection<UUID> tweetIds) {
        if (tweetIds == null || tweetIds.isEmpty()) {
            return List.of();
        }
        return mediaJpaRepository.findByTweetIds(tweetIds)
                .stream()
                .map(MediaEntityMapper::toDomain)
                .toList();
    }

    /**
     * Persists an uploaded media record that is not yet attached to a tweet.
     *
     * @param media the unattached media model (must carry {@code uploadedBy})
     * @return the persisted media model with its generated id
     */
    @Override
    public MediaModel upload(MediaModel media) {
        if (media == null) {
            throw new IllegalArgumentException("media must not be null");
        }

        UserEntity owner = userJpaRepository.getReferenceById(media.getUploadedBy());
        MediaEntity entity = MediaEntityMapper.toPersistence(media, owner);
        MediaEntity saved = mediaJpaRepository.saveAndFlush(entity);
        return MediaEntityMapper.toDomain(saved);
    }

    /**
     * Attaches an uploaded media record to a tweet. Returns {@code true} only
     * when a row was actually attached (i.e. the media existed and was still
     * unattached); {@code false} otherwise.
     *
     * @param mediaId the media record to attach
     * @param tweetId the tweet to attach it to
     * @return true when attached, false when nothing matched
     */
    @Override
    public boolean attachToTweet(UUID mediaId, UUID tweetId) {
        if (mediaId == null || tweetId == null) {
            return false;
        }
        // The bulk update below bypasses the persistence context and executes
        // immediately against the database. When the tweet was created in the
        // same transaction its INSERT may still be pending, which would violate
        // media_tweet_id_fkey — flush pending writes first.
        tweetJpaRepository.flush();
        var tweet = tweetJpaRepository.getReferenceById(tweetId);
        return mediaJpaRepository.attachToTweet(mediaId, tweet) > 0;
    }

    /**
     * Creates media records for a tweet.
     * 
     * Equivalent to legacy MediaDao.createMedia():
     * - Creates Media entity for each URL
     * - Sets tweet reference
     * - Sets displayOrder incrementally
     * - Sets mediaType to IMAGE (legacy behavior)
     *
     * @param tweetId the tweet's ID
     * @param mediaUrls list of media URLs
     * @return list of created media models
     */
    @Override
    public List<MediaModel> createMedia(UUID tweetId, List<String> mediaUrls) {
        if (mediaUrls == null || mediaUrls.isEmpty()) {
            return List.of();
        }

        var tweet = tweetJpaRepository.getReferenceById(tweetId);
        short order = 0;

        List<MediaModel> result = new java.util.ArrayList<>();
        for (String url : mediaUrls) {
            MediaEntity entity = MediaEntityMapper.toPersistence(url, tweet, order++);
            mediaJpaRepository.save(entity);
            result.add(MediaEntityMapper.toDomain(entity));
        }

        return result;
    }

    /**
     * Deletes a media by its ID.
     * 
     * NOTE: The legacy MediaDao.delete() was empty.
     * This method actually implements the deletion.
     *
     * @param mediaId the media's ID
     */
    @Override
    public void delete(UUID mediaId) {
        mediaJpaRepository.deleteById(mediaId);
    }

    /**
     * Hard-deletes all media records belonging to a tweet.
     * Used during tweet deletion to cascade-delete related media.
     *
     * @param tweetId the tweet whose media should be deleted
     */
    @Override
    public void deleteByTweetId(UUID tweetId) {
        mediaJpaRepository.deleteByTweetId(tweetId);
    }
}
