package Client.Service;

import Client.ClientApplicationContext;
import Client.session.ClientSession;
import Client.transport.SocketClient;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import logic_core.app.dto.request.BookmarkTweetRequest;
import logic_core.app.dto.request.GetBookmarksRequest;
import logic_core.app.dto.request.GetIsBookmarkedRequest;
import logic_core.app.dto.request.UnbookmarkTweetRequest;
import logic_core.app.dto.response.BookmarkResponse;
import logic_core.app.dto.response.GetBookmarksResponse;
import logic_core.app.dto.response.GetIsBookmarkedResponse;
import logic_core.common.result.Result;
import logic_core.infrastructure.transport.RequestEnvelope;
import logic_core.infrastructure.transport.RequestType;
import logic_core.infrastructure.transport.ResponseEnvelope;
import lombok.RequiredArgsConstructor;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Client-side access to the Bookmark System backend routes
 * ({@code TWEET_BOOKMARK}, {@code TWEET_UNBOOKMARK},
 * {@code USER_GET_IS_BOOKMARKED}, {@code BOOKMARKS_GET}).
 *
 * <p>Follows the exact conventions of the other client services: requests are
 * built with the current session token, sent through the existing
 * {@link SocketClient} envelope mechanism, and parsed into the shared backend
 * DTOs. No business logic lives here — bookmarks stay private to the backend.
 */
@RequiredArgsConstructor
public final class BookmarkClientService
{
    private final SocketClient socketClient;
    private final ClientSession session;
    private final ExecutorService networkExecutor;
    private final Gson gson;

    public BookmarkClientService(ClientApplicationContext context)
    {
        this(
                context.socketClient(),
                context.session(),
                context.networkExecutor(),
                new GsonBuilder().serializeNulls().create()
        );
    }

    public CompletableFuture<Result<BookmarkResponse>> bookmark(UUID tweetId)
    {
        BookmarkTweetRequest request =
                new BookmarkTweetRequest(tweetId, session.getToken());

        return execute(
                RequestType.TWEET_BOOKMARK,
                request,
                BookmarkResponse.class
        );
    }

    public CompletableFuture<Result<BookmarkResponse>> unbookmark(UUID tweetId)
    {
        UnbookmarkTweetRequest request =
                new UnbookmarkTweetRequest(tweetId, session.getToken());

        return execute(
                RequestType.TWEET_UNBOOKMARK,
                request,
                BookmarkResponse.class
        );
    }

    public CompletableFuture<Result<GetIsBookmarkedResponse>> isBookmarked(UUID tweetId)
    {
        GetIsBookmarkedRequest request =
                new GetIsBookmarkedRequest(session.getToken(), tweetId);

        return execute(
                RequestType.USER_GET_IS_BOOKMARKED,
                request,
                GetIsBookmarkedResponse.class
        );
    }

    public CompletableFuture<Result<GetBookmarksResponse>> getBookmarks(
            int page,
            int pageSize)
    {
        GetBookmarksRequest request =
                new GetBookmarksRequest(page, pageSize, session.getToken());

        return execute(
                RequestType.BOOKMARKS_GET,
                request,
                GetBookmarksResponse.class
        );
    }

    private <T> CompletableFuture<Result<T>> execute(
            RequestType type,
            Object request,
            Class<T> responseClass)
    {
        return CompletableFuture.supplyAsync(() ->
        {
            try
            {
                socketClient.connect();

                JsonElement payload =
                        gson.toJsonTree(request);

                RequestEnvelope envelope =
                        new RequestEnvelope(
                                UUID.randomUUID(),
                                type,
                                payload,
                                session.getToken()
                        );

                ResponseEnvelope response =
                        socketClient.send(envelope);

                if (response == null)
                {
                    return Result.failure("EMPTY_RESPONSE");
                }

                if (!response.isSuccess())
                {
                    return Result.failure(response.errorMessage());
                }

                T data =
                        gson.fromJson(
                                response.getData(),
                                responseClass
                        );

                return Result.success(data);
            }
            catch (Exception e)
            {
                return Result.failure(e.getMessage());
            }
        }, networkExecutor);
    }
}