package logic_core.domain.repository;

import logic_core.domain.model.MediaModel;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MediaRepository
{
    Optional<MediaModel> findById(UUID mediaId);

    List<MediaModel> findByIds(List<UUID> mediaIds);

    List<MediaModel> findByTweetIds(Collection<UUID> tweetIds);

    boolean existsById(UUID mediaId);

    boolean belongsToUser(UUID mediaId, UUID userId);

    /**
     * Counts media uploaded by a user that are not yet attached to a tweet.
     * Used to assign the next display order for an upload (V2.1 #6).
     */
    long countUnattachedByUser(UUID userId);

    boolean isAlreadyAttached(UUID mediaId);

    /**
     * Persists an uploaded media record that is not yet attached to a tweet.
     * Ownership ({@code uploadedBy}) is recorded by the caller.
     */
    MediaModel upload(MediaModel media);

    /**
     * Attaches an uploaded media record to a tweet. Returns {@code true} when a
     * row was actually attached, {@code false} when no matching unattached
     * media row exists.
     */
    boolean attachToTweet(UUID mediaId, UUID tweetId);

    List<MediaModel> createMedia(UUID tweetId, List<String> mediaUrls);

    void delete(UUID mediaId);

    /**
     * Hard-deletes all media records belonging to a tweet.
     * Used during tweet deletion to cascade-delete related media.
     */
    void deleteByTweetId(UUID tweetId);

}