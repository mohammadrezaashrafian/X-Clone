package logic_core.app.usecase.poll;

import jakarta.transaction.Transactional;
import logic_core.app.dto.request.VotePollRequest;
import logic_core.app.dto.response.PollResponse;
import logic_core.app.mapper.PollMapper;
import logic_core.app.security.AuthLockOrchestrator;
import logic_core.app.security.SessionUserContext;
import logic_core.common.exception.ConflictException;
import logic_core.common.exception.ForbiddenException;
import logic_core.common.exception.NotFoundException;
import logic_core.common.exception.ValidationException;
import logic_core.common.result.Result;
import logic_core.common.util.TimeProvider;
import logic_core.domain.model.PollModel;
import logic_core.domain.model.PollOptionModel;
import logic_core.domain.model.PollVote;
import logic_core.domain.model.TweetModel;
import logic_core.domain.policy.InteractionPolicy;
import logic_core.domain.repository.PollRepository;
import logic_core.domain.repository.TweetRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Casts a single user's vote on a poll (POLL_VOTE).
 *
 * <p>The actor is derived from the authenticated session token, never from a
 * caller-supplied field. One vote per user per poll is enforced both at the
 * application layer (existence check) and by the {@code poll_votes} primary
 * key {@code (poll_id, user_id)} at the database level, so a race cannot
 * create duplicate votes. Voting is gated by the same base interaction
 * barrier as liking (active users, block barrier) and rejected once the poll
 * has expired; results remain readable after expiry.
 */
@Service
@RequiredArgsConstructor
public class VotePollUseCase
{
    @NonNull private final InteractionPolicy interactionPolicy;
    @NonNull private final PollRepository pollRepository;
    @NonNull private final TweetRepository tweetRepository;
    @NonNull private final AuthLockOrchestrator lockOrchestrator;
    @NonNull private final TimeProvider timeProvider;

    @Transactional
    public Result<PollResponse> execute(VotePollRequest request)
    {
        if (request == null || request.pollId() == null || request.optionId() == null)
        {
            return Result.failure("Poll ID and option ID are required.");
        }

        try
        {
            SessionUserContext context =
                    lockOrchestrator.lockAndGetContextByToken(
                            request.sessionToken()
                    );

            UUID userId = context.lockedUser().getId();

            PollModel poll = pollRepository.findById(request.pollId())
                    .orElseThrow(() ->
                            new NotFoundException("Poll not found.")
                    );

            // The poll belongs to a tweet; verify it is still active before
            // allowing a vote (deleted/inactive tweet → poll unavailable).
            TweetModel tweet =
                    tweetRepository.findActiveByIdForUpdate(poll.getTweetId())
                            .orElseThrow(() ->
                                    new NotFoundException(
                                            "Tweet not found or unavailable."
                                    )
                            );

            interactionPolicy.validatePollVote(userId, tweet.getAuthorId());

            // Expiry: reject voting once the poll has closed.
            if (poll.getExpiresAt().isBefore(timeProvider.now()))
            {
                throw new ValidationException("Poll has expired.");
            }

            boolean optionBelongsToPoll = poll.getOptions().stream()
                    .map(PollOptionModel::getId)
                    .anyMatch(id -> id.equals(request.optionId()));

            if (!optionBelongsToPoll)
            {
                throw new ValidationException("Option does not belong to this poll.");
            }

            // Duplicate-vote prevention: reject here for a clean error, and the
            // (poll_id, user_id) DB primary key is the authoritative guard.
            if (pollRepository.existsVote(request.pollId(), userId))
            {
                throw new ConflictException("You have already voted in this poll.");
            }

            OffsetDateTime now = timeProvider.now();

            pollRepository.saveVote(
                    PollVote.create(
                            request.pollId(),
                            userId,
                            request.optionId(),
                            now
                    )
            );

            PollModel refreshed = pollRepository.findById(request.pollId())
                    .orElse(poll);

            return Result.success(PollMapper.toResponse(refreshed));
        }
        catch (ValidationException | ForbiddenException | ConflictException | NotFoundException e)
        {
            return Result.failure(e.getMessage());
        }
        catch (Exception e)
        {
            return Result.failure("Failed to process vote: " + e.getMessage());
        }
    }
}