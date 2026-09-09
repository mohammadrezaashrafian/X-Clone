package logic_core.app.usecase.bookmark;

import logic_core.app.dto.request.GetBookmarksRequest;
import logic_core.app.dto.response.GetBookmarksResponse;
import logic_core.app.dto.timeline.TimelineTweet;
import logic_core.app.security.AuthLockOrchestrator;
import logic_core.app.security.SessionUserContext;
import logic_core.common.exception.ForbiddenException;
import logic_core.common.exception.NotFoundException;
import logic_core.common.exception.ValidationException;
import logic_core.common.result.Result;
import logic_core.domain.repository.BookmarkRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GetBookmarksUseCase
{
    private static final int MAX_PAGE_SIZE = 100;

    @NonNull private final BookmarkRepository bookmarkRepository;
    @NonNull private final AuthLockOrchestrator lockOrchestrator;

    public Result<GetBookmarksResponse> execute(GetBookmarksRequest request)
    {
        try
        {
            SessionUserContext context =
                    lockOrchestrator.lockAndGetContextByToken(
                            request.sessionToken()
                    );

            // Only the authenticated user's own private bookmarks are ever
            // considered; there is no caller-supplied owner identifier.
            UUID userId = context.lockedUser().getId();

            int page = request == null ? 0 : Math.max(request.page(), 0);
            int pageSize = request == null
                    ? 20
                    : clampPageSize(request.pageSize());

            int offset = page * pageSize;

            List<TimelineTweet> tweets =
                    bookmarkRepository.getBookmarks(userId, pageSize, offset);

            long totalItems = bookmarkRepository.countBookmarks(userId);

            boolean hasNext = offset + tweets.size() < totalItems;

            return Result.success(
                    GetBookmarksResponse.builder()
                            .tweets(tweets)
                            .totalItems(totalItems)
                            .page(page)
                            .pageSize(pageSize)
                            .hasNext(hasNext)
                            .build()
            );
        }
        catch (ValidationException | ForbiddenException | NotFoundException e)
        {
            return Result.failure(e.getMessage());
        }
    }

    private static int clampPageSize(int pageSize)
    {
        if (pageSize <= 0)
        {
            return 20;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }
}