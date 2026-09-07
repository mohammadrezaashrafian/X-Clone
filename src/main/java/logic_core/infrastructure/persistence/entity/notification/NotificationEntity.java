package logic_core.infrastructure.persistence.entity.notification;

import jakarta.persistence.*;
import logic_core.domain.model.notification.NotificationType;
import logic_core.infrastructure.persistence.base.BaseEntity;
import logic_core.infrastructure.persistence.entity.tweet.TweetEntity;
import logic_core.infrastructure.persistence.entity.UserEntity;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * JPA mapping for the existing {@code notifications} table:
 *
 * <pre>
 * id           uuid PK NOT NULL
 * created_at   timestamptz NOT NULL
 * is_read      bool NOT NULL DEFAULT false
 * type         varchar(30) NOT NULL
 * actor_id     uuid NULL        → users.id ON DELETE SET NULL
 * recipient_id uuid NOT NULL    → users.id ON DELETE CASCADE
 * tweet_id     uuid NULL        → tweets.id ON DELETE CASCADE
 * </pre>
 *
 * <p>The table already exists in the database (part of the V2 baselined
 * schema); this entity maps to it — no DDL is introduced here.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "notifications", indexes = {
        @Index(name = "idx_notifications_recipient_id_is_read", columnList = "recipient_id, is_read"),
        @Index(name = "idx_notifications_actor_id", columnList = "actor_id"),
        @Index(name = "idx_notifications_tweet_id", columnList = "tweet_id")
})
public class NotificationEntity extends BaseEntity
{
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private UserEntity recipient;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "actor_id", nullable = true)
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private UserEntity actor;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "tweet_id", nullable = true)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private TweetEntity tweet;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private NotificationType type;

    @Column(name = "is_read", nullable = false)
    private boolean isRead = false;
}