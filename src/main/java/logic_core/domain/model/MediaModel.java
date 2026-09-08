package logic_core.domain.model;

import logic_core.domain.model.media.MediaType;
import lombok.*;

import java.util.UUID;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class MediaModel
{
    private UUID mediaId;
    private UUID tweetId;
    private String mediaUrl;
    private String originalFilename;
    private Long fileSizeBytes;
    private MediaType mediaType;
    private short displayOrder;

    /**
     * Owner of an uploaded-but-not-yet-attached media record. Null for
     * attached media created through the legacy URL-based flow.
     */
    private UUID uploadedBy;

    public boolean isImage()
    {
        return mediaType == MediaType.IMAGE;
    }

    public boolean isVideo()
    {
        return mediaType == MediaType.VIDEO;
    }

    public boolean isGif()
    {
        return mediaType == MediaType.GIF;
    }

    public boolean isAttachedToTweet()
    {
        return tweetId != null;
    }

    public boolean hasMediaUrl()
    {
        return mediaUrl != null && !mediaUrl.isBlank();
    }

    public boolean hasOriginalFilename()
    {
        return originalFilename != null && !originalFilename.isBlank();
    }

    public boolean hasValidFileSize()
    {
        return fileSizeBytes != null && fileSizeBytes >= 0;
    }

    public boolean isOwnedBy(UUID userId)
    {
        return uploadedBy != null && uploadedBy.equals(userId);
    }

    public void attachToTweet(UUID tweetId)
    {
        if (tweetId == null)
        {
            throw new IllegalArgumentException("tweetId cannot be null");
        }
        if (this.tweetId != null && !this.tweetId.equals(tweetId))
        {
            throw new IllegalStateException("Media is already attached to another tweet");
        }
        this.tweetId = tweetId;
    }

    public void changeDisplayOrder(short newDisplayOrder)
    {
        if (newDisplayOrder < 0)
        {
            throw new IllegalArgumentException("displayOrder cannot be negative");
        }
        this.displayOrder = newDisplayOrder;
    }

    public void updateMediaUrl(String mediaUrl)
    {
        if (mediaUrl == null || mediaUrl.isBlank())
        {
            throw new IllegalArgumentException("mediaUrl cannot be blank");
        }
        this.mediaUrl = mediaUrl;
    }
}