package logic_core.domain.model;

import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Domain representation of a single user's vote on a poll. One vote per user
 * per poll is enforced by the {@code poll_votes} primary key
 * ({@code (poll_id, user_id)}).
 */
@Getter
public class PollVote
{
    private final UUID pollId;
    private final UUID userId;
    private final UUID optionId;
    private final OffsetDateTime votedAt;

    private PollVote(UUID pollId, UUID userId, UUID optionId, OffsetDateTime votedAt)
    {
        this.pollId = Objects.requireNonNull(pollId, "PollVote.pollId cannot be null");
        this.userId = Objects.requireNonNull(userId, "PollVote.userId cannot be null");
        this.optionId = Objects.requireNonNull(optionId, "PollVote.optionId cannot be null");
        this.votedAt = Objects.requireNonNull(votedAt, "PollVote.votedAt cannot be null");
    }

    public static PollVote create(UUID pollId, UUID userId, UUID optionId, OffsetDateTime votedAt)
    {
        return new PollVote(pollId, userId, optionId, votedAt);
    }
}