package logic_core.app.usecase.bookmark;

import jakarta.transaction.Transactional;
import logic_core.app.dto.request.UnbookmarkTweetRequest;
import logic_core.app.dto.response.BookmarkResponse;
import logic_core.app.security.AuthLockOrchestrator;
import logic_core.app.security.SessionUserContext;
import logic_core.common.exception.AppException;
import logic_core.common.exception.NotFoundException;
import logic_core.common.result.Result;
import logic_core.domain.model.TweetModel;
import logic_core.domain.policy.InteractionPolicy;
import logic_core.domain.repository.BookmarkRepository;
import logic_core.domain.repository.TweetRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UnbookmarkTweetUseCase
{
    @NonNull private final InteractionPolicy interactionPolicy;
    @NonNull private final TweetRepository tweetRepository;
    @NonNull private final BookmarkRepository bookmarkRepository;
    @NonNull private final AuthLockOrchestrator lockOrchestrator;

    @Transactional
    public Result<BookmarkResponse> execute(UnbookmarkTweetRequest request)
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

            UUID userId = context.lockedUser().getId();

            TweetModel tweet = tweetRepository.findActiveByIdForUpdate(request.tweetId())
                    .orElseThrow(() ->
                            new NotFoundException("Tweet not found or unavailable.")
                    );

            interactionPolicy.validateUnbookmark(
                    userId,
                    tweet.getId(),
                    tweet.getAuthorId()
            );

            if (!bookmarkRepository.isBookmarked(userId, tweet.getId()))
            {
                throw new NotFoundException("Bookmark relation does not exist.");
            }

            bookmarkRepository.findBookmark(userId, tweet.getId())
                    .ifPresent(bookmarkRepository::deleteBookmark);

            return Result.success(new BookmarkResponse(false));
        }
        catch (AppException e)
        {
            return Result.failure(e.getMessage());
        }
        catch (Exception e)
        {
            return Result.failure("Failed to process unbookmark operation.");
        }
    }
}