package logic_core.domain.model;

import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain representation of a poll attached to a tweet. One poll per tweet is
 * enforced by the {@code uq_polls_tweet_id} database unique index. Persistence
 * lives in the infrastructure layer; this model carries no JPA annotations.
 */
@Getter
public class PollModel
{
    private final UUID id;
    private final UUID tweetId;
    private final String question;
    private final OffsetDateTime expiresAt;
    private final OffsetDateTime createdAt;
    private final List<PollOptionModel> options;
    private final long totalVotes;

    private PollModel(
            UUID id,
            UUID tweetId,
            String question,
            OffsetDateTime expiresAt,
            OffsetDateTime createdAt,
            List<PollOptionModel> options,
            long totalVotes)
    {
        this.id = Objects.requireNonNull(id, "Poll.id cannot be null");
        this.tweetId = Objects.requireNonNull(tweetId, "Poll.tweetId cannot be null");
        this.question = Objects.requireNonNull(question, "Poll.question cannot be null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "Poll.expiresAt cannot be null");
        this.createdAt = Objects.requireNonNull(createdAt, "Poll.createdAt cannot be null");
        this.options = options == null ? List.of() : List.copyOf(options);
        this.totalVotes = totalVotes;
    }

    public static PollModel create(
            UUID tweetId,
            String question,
            OffsetDateTime expiresAt,
            OffsetDateTime createdAt,
            List<PollOptionModel> options)
    {
        return new PollModel(
                UUID.randomUUID(),
                tweetId,
                question,
                expiresAt,
                createdAt,
                options,
                0L
        );
    }

    public static PollModel restore(
            UUID id,
            UUID tweetId,
            String question,
            OffsetDateTime expiresAt,
            OffsetDateTime createdAt,
            List<PollOptionModel> options,
            long totalVotes)
    {
        return new PollModel(
                id,
                tweetId,
                question,
                expiresAt,
                createdAt,
                options,
                totalVotes
        );
    }
}