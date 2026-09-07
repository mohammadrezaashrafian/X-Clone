package logic_core.domain.model;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain representation of a private bookmark: a user-owned relationship to a
 * tweet. Bookmarks are never exposed publicly — only the owning user can see
 * them. Persistence lives in the infrastructure layer; this model carries no
 * JPA annotations.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class BookmarkRelation
{
    private UUID userId;
    private UUID tweetId;
    private OffsetDateTime createdAt;

    public static BookmarkRelation create(UUID userId, UUID tweetId)
    {
        Objects.requireNonNull(userId, "BookmarkRelation.userId cannot be null");
        Objects.requireNonNull(tweetId, "BookmarkRelation.tweetId cannot be null");

        return builder()
                .userId(userId)
                .tweetId(tweetId)
                .createdAt(OffsetDateTime.now())
                .build();
    }
}