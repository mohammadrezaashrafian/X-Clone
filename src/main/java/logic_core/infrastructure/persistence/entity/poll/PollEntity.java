package logic_core.infrastructure.persistence.entity.poll;

import jakarta.persistence.*;
import logic_core.infrastructure.persistence.base.BaseEntity;
import logic_core.infrastructure.persistence.entity.tweet.TweetEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * JPA mapping for the existing {@code polls} table (part of the V2 baselined
 * schema):
 *
 * <pre>
 * id         uuid PRIMARY KEY NOT NULL
 * created_at timestamptz NOT NULL
 * tweet_id   uuid NOT NULL → tweets.id ON DELETE CASCADE
 * question   varchar(255) NOT NULL
 * expires_at timestamptz NOT NULL
 * </pre>
 *
 * <p>One poll per tweet is enforced by the database's unique index
 * {@code uq_polls_tweet_id}.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "polls", indexes = {
        @Index(name = "uq_polls_tweet_id", columnList = "tweet_id", unique = true)
})
public class PollEntity extends BaseEntity
{
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tweet_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private TweetEntity tweet;

    @Column(name = "question", nullable = false, length = 255)
    private String question;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @BatchSize(size = 20)
    @OneToMany(mappedBy = "poll", fetch = FetchType.LAZY,
            cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder ASC")
    private List<PollOptionEntity> options = new ArrayList<>();
}