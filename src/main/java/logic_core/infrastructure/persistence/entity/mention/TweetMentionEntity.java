package logic_core.infrastructure.persistence.entity.mention;

import jakarta.persistence.*;
import logic_core.infrastructure.persistence.entity.UserEntity;
import logic_core.infrastructure.persistence.entity.tweet.TweetEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * JPA mapping for the existing {@code tweet_mentions} table (part of the V2
 * baselined schema):
 *
 * <pre>
 * mentioned_user_id uuid NOT NULL → users.id ON DELETE CASCADE
 * tweet_id          uuid NOT NULL → tweets.id ON DELETE CASCADE
 * PRIMARY KEY (mentioned_user_id, tweet_id)
 * </pre>
 *
 * <p>The table intentionally carries no {@code created_at} column, so this
 * entity maps only the two FK columns.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "tweet_mentions", indexes = {
        @Index(name = "idx_tweet_mentions_mentioned_user_id", columnList = "mentioned_user_id")
})
@IdClass(TweetMentionEntityId.class)
public class TweetMentionEntity
{
    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mentioned_user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private UserEntity mentionedUser;

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tweet_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private TweetEntity tweet;
}