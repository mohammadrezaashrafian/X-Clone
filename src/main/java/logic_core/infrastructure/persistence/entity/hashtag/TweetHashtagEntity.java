package logic_core.infrastructure.persistence.entity.hashtag;

import jakarta.persistence.*;
import logic_core.infrastructure.persistence.entity.tweet.TweetEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * JPA mapping for the existing {@code tweet_hashtags} table:
 *
 * <pre>
 * hashtag_id  uuid NOT NULL → hashtags.id ON DELETE CASCADE
 * tweet_id    uuid NOT NULL → tweets.id ON DELETE CASCADE
 * usage_count integer NOT NULL
 * PRIMARY KEY (hashtag_id, tweet_id)
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "tweet_hashtags", indexes = {
        @Index(name = "idx_tweet_hashtags_hashtag_id", columnList = "hashtag_id")
})
@IdClass(TweetHashtagEntityId.class)
public class TweetHashtagEntity
{
    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hashtag_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private HashtagEntity hashtag;

    @Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tweet_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private TweetEntity tweet;

    @Column(name = "usage_count", nullable = false)
    private int usageCount;
}