package logic_core.infrastructure.mapper;

import logic_core.domain.model.MediaModel;
import logic_core.infrastructure.persistence.entity.media.MediaEntity;
import logic_core.domain.model.media.MediaType;
import logic_core.infrastructure.persistence.entity.UserEntity;
import logic_core.infrastructure.persistence.entity.tweet.TweetEntity;

public final class MediaEntityMapper {

    private MediaEntityMapper() {
    }

    /**
     * Maps a MediaEntity to the domain MediaModel.
     *
     * Extracts tweet UUID from the entity's TweetEntity reference (nullable for
     * uploaded-but-unattached media) and preserves all media metadata and the
     * upload owner.
     */
    public static MediaModel toDomain(MediaEntity entity) {
        if (entity == null) {
            return null;
        }

        return MediaModel.builder()
                .mediaId(entity.getId())
                .tweetId(entity.getTweet() != null ? entity.getTweet().getId() : null)
                .mediaUrl(entity.getMediaURL())
                .originalFilename(entity.getOriginalFilename())
                .fileSizeBytes(entity.getFileSizeBytes())
                .mediaType(entity.getMediaType())
                .displayOrder(entity.getDisplayOrder())
                .uploadedBy(entity.getUploadedBy() != null ? entity.getUploadedBy().getId() : null)
                .build();
    }

    /**
     * Creates a new MediaEntity from a media URL and tweet reference.
     *
     * Used by createMedia() to create media records.
     * The caller must provide a resolved TweetEntity reference.
     */
    public static MediaEntity toPersistence(
            String mediaUrl,
            TweetEntity tweet,
            short displayOrder) {
        if (mediaUrl == null || tweet == null) {
            return null;
        }

        MediaEntity entity = new MediaEntity();
        entity.setTweet(tweet);
        entity.setMediaURL(mediaUrl);
        entity.setOriginalFilename(null);
        entity.setFileSizeBytes(0L);
        entity.setMediaType(MediaType.IMAGE);
        entity.setDisplayOrder(displayOrder);
        return entity;
    }

    /**
     * Creates an unattached MediaEntity for an uploaded file, recording the
     * owning user. Used by the upload-before-attach flow (V2.1 #6).
     */
    public static MediaEntity toPersistence(
            MediaModel model,
            UserEntity uploadedBy) {
        if (model == null) {
            return null;
        }

        MediaEntity entity = new MediaEntity();
        entity.setTweet(null);
        entity.setUploadedBy(uploadedBy);
        entity.setMediaURL(model.getMediaUrl());
        entity.setOriginalFilename(model.getOriginalFilename());
        entity.setFileSizeBytes(model.getFileSizeBytes());
        entity.setMediaType(model.getMediaType());
        entity.setDisplayOrder(model.getDisplayOrder());
        return entity;
    }
}