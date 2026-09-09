package logic_core.app.usecase.tweet;

import logic_core.app.dto.request.SearchTweetsRequest;
import logic_core.app.dto.response.TweetSearchResponse;
import logic_core.app.dto.timeline.TimelineTweet;
import logic_core.app.security.AuthLockOrchestrator;
import logic_core.app.security.SessionUserContext;
import logic_core.common.exception.AppException;
import logic_core.common.exception.ValidationException;
import logic_core.common.result.Result;
import logic_core.domain.repository.TweetRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Case-insensitive substring search over active tweet content (TWEET_SEARCH).
 *
 * <p>The actor is derived from the authenticated session token — never from a
 * caller-supplied field. Visibility and lifecycle rules (soft-delete, author
 * existence, bidirectional blocks, mutes, retweet markers) are enforced inside
 * the repository query itself, mirroring the timeline semantics; no second
 * authorization layer exists or is introduced here.
 *
 * <p>Pagination follows the modern V2.1 convention: zero-based page,
 * page size clamped to 1..100 (default 20),
 * {@code hasNext = offset + items.size() < totalItems}.
 */
@Service
@RequiredArgsConstructor
public class SearchTweetsUseCase
{
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_QUERY_LENGTH = 100;

    @NonNull private final TweetRepository tweetRepository;
    @NonNull private final AuthLockOrchestrator lockOrchestrator;

    public Result<TweetSearchResponse> execute(SearchTweetsRequest request)
    {
        try
        {
            SessionUserContext context =
                    lockOrchestrator.lockAndGetContextByToken(
                            request.sessionToken()
                    );

            UUID actorId = context.lockedUser().getId();

            if (request.query() == null || request.query().isBlank())
            {
                throw new ValidationException("Search query is required.");
            }

            if (request.query().length() > MAX_QUERY_LENGTH)
            {
                throw new ValidationException("Search query is too long.");
            }

            String term = request.query().trim();

            int page = Math.max(request.page(), 0);
            int pageSize = clampPageSize(request.pageSize());
            int offset = page * pageSize;

            List<TimelineTweet> tweets =
                    tweetRepository.searchTweets(actorId, term, pageSize, offset);

            long totalItems =
                    tweetRepository.countSearchTweets(actorId, term);

            boolean hasNext = offset + tweets.size() < totalItems;

            return Result.success(
                    TweetSearchResponse.builder()
                            .tweets(tweets)
                            .totalItems(totalItems)
                            .page(page)
                            .pageSize(pageSize)
                            .hasNext(hasNext)
                            .build()
            );
        }
        catch (AppException e)
        {
            return Result.failure(e.getMessage());
        }
    }

    private static int clampPageSize(int pageSize)
    {
        if (pageSize <= 0)
        {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }
}
