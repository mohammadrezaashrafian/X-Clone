package logic_core.infrastructure.persistence.entity.poll;

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
 * JPA mapping for the existing {@code poll_votes} table:
 *
 * <pre>
 * poll_id   uuid NOT NULL → polls.id ON DELETE CASCADE
 * user_id   uuid NOT NULL → users.id ON DELETE CASCADE
 * option_id uuid NOT NULL → poll_options.id ON DELETE CASCADE
 * voted_at  timestamptz NOT NULL
 * PRIMARY KEY (poll_id, user_id)
 * </pre>
 *
 * <p>The primary key enforces one vote per user per poll at the database level.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "poll_votes", indexes = {
        @Index(name = "idx_poll_votes_poll_id", columnList = "poll_id"),
        @Index(name = "idx_poll_votes_option_id", columnList = "option_id")
})
@IdClass(PollVoteEntityId.class)
public class PollVoteEntity
{
    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "poll_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private PollEntity poll;

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private UserEntity user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "option_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private PollOptionEntity option;

    @CreationTimestamp
    @Column(name = "voted_at", updatable = false)
    private OffsetDateTime votedAt;
}