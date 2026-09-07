package logic_core.app.usecase.tweet;

import jakarta.transaction.Transactional;
import logic_core.app.dto.request.UnretweetRequest;
import logic_core.app.dto.response.TweetResponse;
import logic_core.app.dto.response.UserSummaryResponse;
import logic_core.app.mapper.TweetMapper;
import logic_core.app.mapper.UserSummaryResponseMapper;
import logic_core.app.security.AuthLockOrchestrator;
import logic_core.app.security.SessionUserContext;
import logic_core.common.exception.AppException;
import logic_core.common.exception.NotFoundException;
import logic_core.common.result.Result;
import logic_core.domain.model.TweetModel;
import logic_core.domain.model.UserModel;
import logic_core.domain.policy.InteractionPolicy;
import logic_core.domain.repository.RelationshipRepository;
import logic_core.domain.repository.TweetRepository;
import logic_core.domain.repository.UserRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Removes the authenticated user's own retweet of a tweet (TWEET_UNRETWEET).
 *
 * <p>The actor is derived from the authenticated session token, never from a
 * caller-supplied field. Only the user's own active retweet marker row for the
 * target tweet is removed — the original tweet, other users' retweets, and
 * unrelated retweets are never touched. Retweet counts are derived from active
 * retweet rows, so removing the marker row is the count update; no separate
 * counter is decremented.
 */
@Service
@RequiredArgsConstructor
public class UnretweetUseCase
{
    @NonNull private final InteractionPolicy interactionPolicy;
    @NonNull private final TweetRepository tweetRepository;
    @NonNull private final UserRepository userRepository;
    @NonNull private final RelationshipRepository relationshipRepository;
    @NonNull private final AuthLockOrchestrator lockOrchestrator;

    @Transactional
    public Result<TweetResponse> execute(UnretweetRequest request)
    {
        if (request == null || request.tweetId() == null)
        {
            return Result.failure("Tweet ID is required.");
        }

        try
        {
            SessionUserContext context =
                    lockOrchestrator.lockAndGetContextByToken(
                            request.sessionToken()
                    );

            UUID currentUserId = context.lockedUser().getId();

            TweetModel originalTweet =
                    tweetRepository.findActiveByIdForUpdate(
                                    request.tweetId()
                            )
                            .orElseThrow(() ->
                                    new NotFoundException(
                                            "Tweet not found or deleted."
                                    )
                            );

            interactionPolicy.validateUnretweet(
                    currentUserId,
                    originalTweet.getAuthorId(),
                    originalTweet.getId()
            );

            int removed =
                    tweetRepository.deleteActiveRetweetByUser(
                            originalTweet.getId(),
                            currentUserId
                    );

            if (removed <= 0)
            {
                return Result.failure("You have not retweeted this tweet.");
            }

            TweetResponse response =
                    buildResponse(originalTweet);

            return Result.success(response);
        }
        catch (AppException e)
        {
            return Result.failure(e.getMessage());
        }
        catch (Exception e)
        {
            return Result.failure("Failed to process un-retweet.");
        }
    }

    private TweetResponse buildResponse(TweetModel originalTweet)
    {
        UserModel author =
                userRepository.findById(originalTweet.getAuthorId())
                        .orElse(null);

        UserSummaryResponse authorSummary =
                author != null
                        ? UserSummaryResponseMapper.toResponse(author)
                        : null;

        TweetModel enrichedTweet =
                originalTweet.toBuilder()
                        .likeCount(
                                relationshipRepository.countLikesByTweetId(
                                        originalTweet.getId()
                                )
                        )
                        .replyCount(
                                tweetRepository.countRepliesByTweetId(
                                        originalTweet.getId()
                                )
                        )
                        .retweetCount(
                                tweetRepository.countRetweetsByTweetId(
                                        originalTweet.getId()
                                )
                        )
                        .build();

        return TweetMapper.toResponse(
                enrichedTweet,
                authorSummary,
                null,
                null,
                null,
                null
        );
    }
}