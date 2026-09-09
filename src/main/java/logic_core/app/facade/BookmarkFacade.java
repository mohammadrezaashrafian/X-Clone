package logic_core.app.facade;

import logic_core.app.dto.request.BookmarkTweetRequest;
import logic_core.app.dto.request.GetBookmarksRequest;
import logic_core.app.dto.request.GetIsBookmarkedRequest;
import logic_core.app.dto.request.UnbookmarkTweetRequest;
import logic_core.app.dto.response.BookmarkResponse;
import logic_core.app.dto.response.GetBookmarksResponse;
import logic_core.app.dto.response.GetIsBookmarkedResponse;
import logic_core.app.usecase.bookmark.BookmarkTweetUseCase;
import logic_core.app.usecase.bookmark.GetBookmarksUseCase;
import logic_core.app.usecase.bookmark.GetIsBookmarkedUseCase;
import logic_core.app.usecase.bookmark.UnbookmarkTweetUseCase;
import logic_core.common.result.Result;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class BookmarkFacade
{
    private final BookmarkTweetUseCase bookmarkTweetUseCase;
    private final UnbookmarkTweetUseCase unbookmarkTweetUseCase;
    private final GetIsBookmarkedUseCase getIsBookmarkedUseCase;
    private final GetBookmarksUseCase getBookmarksUseCase;

    public Result<BookmarkResponse> bookmark(BookmarkTweetRequest request)
    {
        return bookmarkTweetUseCase.execute(request);
    }

    public Result<BookmarkResponse> unbookmark(UnbookmarkTweetRequest request)
    {
        return unbookmarkTweetUseCase.execute(request);
    }

    public Result<GetIsBookmarkedResponse> isBookmarked(GetIsBookmarkedRequest request)
    {
        return getIsBookmarkedUseCase.execute(request);
    }

    public Result<GetBookmarksResponse> getBookmarks(GetBookmarksRequest request)
    {
        return getBookmarksUseCase.execute(request);
    }
}