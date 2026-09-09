package logic_core.app.usecase.tweet;

import logic_core.app.cache.CacheJsonCodec;
import logic_core.app.cache.CacheKeys;
import logic_core.app.cache.CachePolicy;
import logic_core.app.cache.CacheService;
import logic_core.app.dto.request.GetTweetRequest;
import logic_core.app.dto.timeline.TimelineTweet;
import logic_core.app.security.AuthLockOrchestrator;
import logic_core.app.security.SessionUserContext;
import logic_core.common.exception.AppException;
import logic_core.common.result.Result;
import logic_core.domain.repository.TweetRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(GetTweetUseCase.class);

    @NonNull private final TweetRepository tweetRepository;
    @NonNull private final AuthLockOrchestrator lockOrchestrator;
    @NonNull private final CacheService cacheService;
    @NonNull private final CacheJsonCodec cacheJsonCodec;

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

            // Actor-scoped key: visibility (block/mute) is evaluated per viewer
            // inside the repository query, so a cached entry is only valid for
            // the actor it was loaded for. (V2.1 #19)
            String cacheKey = CacheKeys.tweet(actorId, request.tweetId());

            String cached = cacheService.get(cacheKey);
            if (cached != null)
            {
                TimelineTweet cachedTweet = safeFromJson(cached, cacheKey);
                if (cachedTweet != null)
                {
                    return Result.success(cachedTweet);
                }
            }

            TimelineTweet tweet = tweetRepository.findSingleTweet(actorId, request.tweetId())
                    .orElseGet(() -> {
                        cacheService.evict(cacheKey);
                        return null;
                    });

            if (tweet == null)
            {
                return Result.failure("Tweet not found or deleted.");
            }

            cacheService.put(cacheKey, cacheJsonCodec.toJson(tweet), CachePolicy.TWEET);

            return Result.success(tweet);
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

    /**
     * Returns null (treating the entry as a miss) when a cached value cannot
     * be decoded, so corrupt cache data can never turn a healthy database read
     * into a failure response.
     */
    private TimelineTweet safeFromJson(String json, String cacheKey)
    {
        try
        {
            return cacheJsonCodec.fromJson(json, TimelineTweet.class);
        }
        catch (RuntimeException e)
        {
            log.warn("Discarding unreadable cached tweet for key {}: {}", cacheKey, e.toString());
            return null;
        }
    }
}
