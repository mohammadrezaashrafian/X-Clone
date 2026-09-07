package logic_core.infrastructure.persistence.entity.hashtag;

import jakarta.persistence.*;
import logic_core.infrastructure.persistence.entity.UserEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.OffsetDateTime;

/**
 * JPA mapping for the existing {@code hashtag_follows} table:
 *
 * <pre>
 * hashtag_id uuid NOT NULL → hashtags.id ON DELETE CASCADE
 * user_id    uuid NOT NULL → users.id ON DELETE CASCADE
 * created_at timestamptz NOT NULL
 * PRIMARY KEY (hashtag_id, user_id)
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "hashtag_follows", indexes = {
        @Index(name = "idx_hashtag_follows_hashtag_id", columnList = "hashtag_id")
})
@IdClass(HashtagFollowEntityId.class)
public class HashtagFollowEntity
{
    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hashtag_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private HashtagEntity hashtag;

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private UserEntity user;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;
}