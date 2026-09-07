package logic_core.infrastructure.persistence.entity.bookmark;

import jakarta.persistence.*;
import logic_core.infrastructure.persistence.entity.UserEntity;
import logic_core.infrastructure.persistence.entity.tweet.TweetEntity;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.OffsetDateTime;

/**
 * JPA mapping for the {@code bookmarks} table (created by the V2 Flyway
 * migration):
 *
 * <pre>
 * user_id    uuid NOT NULL → users.id ON DELETE CASCADE
 * tweet_id   uuid NOT NULL → tweets.id ON DELETE CASCADE
 * created_at timestamptz NOT NULL
 * PRIMARY KEY (user_id, tweet_id)
 * </pre>
 *
 * <p>Bookmarks are private — the owning user is the only reader.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "bookmarks", indexes = {
        @Index(name = "idx_bookmarks_tweet_id", columnList = "tweet_id"),
        @Index(name = "idx_bookmarks_user_id_created_at", columnList = "user_id, created_at")
})
@IdClass(BookmarkEntityId.class)
public class BookmarkEntity
{
    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private UserEntity user;

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tweet_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private TweetEntity tweet;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;
}