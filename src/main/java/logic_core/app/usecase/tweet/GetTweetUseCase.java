package logic_core.app.usecase.tweet;

import logic_core.app.dto.request.GetTweetRequest;
import logic_core.app.dto.timeline.TimelineTweet;
import logic_core.app.security.AuthLockOrchestrator;
import logic_core.app.security.SessionUserContext;
import logic_core.common.exception.AppException;
import logic_core.common.result.Result;
import logic_core.domain.repository.TweetRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Retrieves a single active tweet by id (TWEET_GET).
 *
 * <p>The actor is derived from the authenticated session token, never from a
 * caller-supplied field. Visibility mirrors the timeline/reply-thread rules:
 * the repository only returns the tweet when it is not soft-deleted and no
 * block relation exists (in either direction) between the actor and the tweet
 * author; any other case surfaces as a not-found style failure so no internal
 * entity or relation details are exposed.
 */
@Service
@RequiredArgsConstructor
public class GetTweetUseCase
{
    @NonNull private final TweetRepository tweetRepository;
    @NonNull private final AuthLockOrchestrator lockOrchestrator;

    public Result<TimelineTweet> execute(GetTweetRequest request)
    {
        if (request == null || request.tweetId() == null)
        {
            return Result.failure("Tweet ID is required.");
        }

        try
        {
            SessionUserContext context = lockOrchestrator.lockAndGetContextByToken(request.token());

            UUID actorId = context.lockedUser().getId();

            return tweetRepository.findSingleTweet(actorId, request.tweetId())
                    .map(Result::success)
                    .orElseGet(() -> Result.failure("Tweet not found or deleted."));
        }
        catch (AppException e)
        {
            return Result.failure(e.getMessage());
        }
        catch (Exception e)
        {
            return Result.failure("Failed to load tweet.");
        }
    }
}
