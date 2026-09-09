package logic_core.domain.model;

import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain representation of a hashtag — the canonical (normalized) tag string
 * plus its identity and creation time. Persistence lives in the infrastructure
 * layer; this model carries no JPA annotations.
 */
@Getter
public class HashtagModel
{
    private final UUID id;
    private final String tag;
    private final OffsetDateTime createdAt;

    private HashtagModel(UUID id, String tag, OffsetDateTime createdAt)
    {
        this.id = Objects.requireNonNull(id, "Hashtag.id cannot be null");
        this.tag = Objects.requireNonNull(tag, "Hashtag.tag cannot be null");
        this.createdAt = Objects.requireNonNull(createdAt, "Hashtag.createdAt cannot be null");
    }

    /**
     * Factory for a brand-new hashtag about to be persisted.
     */
    public static HashtagModel create(String tag, OffsetDateTime createdAt)
    {
        return new HashtagModel(UUID.randomUUID(), tag, createdAt);
    }

    /**
     * Factory used when reconstructing an already-persisted hashtag.
     */
    public static HashtagModel restore(UUID id, String tag, OffsetDateTime createdAt)
    {
        return new HashtagModel(id, tag, createdAt);
    }
}