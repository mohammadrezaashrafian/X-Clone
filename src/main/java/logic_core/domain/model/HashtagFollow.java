package logic_core.domain.model;

import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain representation of a user's subscription to a hashtag.
 */
@Getter
public class HashtagFollow
{
    private final UUID hashtagId;
    private final UUID userId;
    private final OffsetDateTime createdAt;

    private HashtagFollow(UUID hashtagId, UUID userId, OffsetDateTime createdAt)
    {
        this.hashtagId = Objects.requireNonNull(hashtagId, "HashtagFollow.hashtagId cannot be null");
        this.userId = Objects.requireNonNull(userId, "HashtagFollow.userId cannot be null");
        this.createdAt = Objects.requireNonNull(createdAt, "HashtagFollow.createdAt cannot be null");
    }

    public static HashtagFollow create(UUID userId, UUID hashtagId, OffsetDateTime createdAt)
    {
        return new HashtagFollow(hashtagId, userId, createdAt);
    }
}