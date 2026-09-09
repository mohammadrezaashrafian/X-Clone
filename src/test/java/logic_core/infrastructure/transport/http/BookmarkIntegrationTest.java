package logic_core.infrastructure.transport.http;

import com.google.gson.Gson;
import logic_core.app.dto.request.BlockUserRequest;
import logic_core.app.dto.request.BookmarkTweetRequest;
import logic_core.app.dto.request.CreateTweetRequest;
import logic_core.app.dto.request.DeleteTweetRequest;
import logic_core.app.dto.request.GetBookmarksRequest;
import logic_core.app.dto.request.GetIsBookmarkedRequest;
import logic_core.app.dto.request.MuteUserRequest;
import logic_core.app.dto.request.RegisterRequest;
import logic_core.app.dto.request.UnbookmarkTweetRequest;
import logic_core.app.dto.response.AuthResponse;
import logic_core.app.dto.response.BookmarkResponse;
import logic_core.app.dto.response.GetBookmarksResponse;
import logic_core.app.dto.response.GetIsBookmarkedResponse;
import logic_core.app.dto.response.TweetResponse;
import logic_core.infrastructure.transport.RequestEnvelope;
import logic_core.infrastructure.transport.RequestType;
import logic_core.infrastructure.transport.ResponseEnvelope;
import logic_core.infrastructure.transport.server.ServerMain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Core integration suite — bookmark system.
 *
 * <p>Drives the full stack ({@code POST /api} → {@code RequestDispatcher} →
 * Facade → UseCase → Repository Adapter → JPA → PostgreSQL) for the V2.1
 * bookmark feature: private bookmark/unbookmark lifecycle, duplicate and
 * isolation rules, viewer-specific bookmark state, paginated private bookmark
 * listing with the existing block/mute/soft-delete visibility semantics, and
 * the Flyway V2 migration that creates the {@code bookmarks} table.
 *
 * <p>Uses the same real-PostgreSQL harness as the other HTTP integration
 * tests: the Spring context boots against {@code xclonedb} (Flyway applies
 * the V1 baseline and the V2 bookmark migration, Hibernate
 * {@code ddl-auto=validate} passes) and every row created here is removed in
 * {@link #cleanUpCreatedRows()}.
 */
@SpringBootTest(classes = ServerMain.class)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BookmarkIntegrationTest
{
    private static final int TEST_SOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerTestProperties(DynamicPropertyRegistry registry)
    {
        registry.add("server.socket.port", () -> TEST_SOCKET_PORT);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Gson gson;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<UUID> createdTweetIds = new ArrayList<>();
    private final List<UUID> createdUserIds = new ArrayList<>();

    @AfterEach
    void cleanUpCreatedRows()
    {
        try
        {
            if (!createdTweetIds.isEmpty())
            {
                String placeholders = repeatPlaceholders(createdTweetIds.size());
                Object[] tweetArgs = createdTweetIds.toArray();

                jdbcTemplate.update(
                        "DELETE FROM bookmarks WHERE tweet_id IN (" + placeholders + ")",
                        tweetArgs);
                jdbcTemplate.update(
                        "DELETE FROM tweet_mentions WHERE tweet_id IN (" + placeholders + ")",
                        tweetArgs);
                jdbcTemplate.update(
                        "DELETE FROM likes WHERE tweet_id IN (" + placeholders + ")",
                        tweetArgs);
                jdbcTemplate.update(
                        "DELETE FROM tweets WHERE id IN (" + placeholders + ")",
                        tweetArgs);
            }
            if (!createdUserIds.isEmpty())
            {
                String placeholders = repeatPlaceholders(createdUserIds.size());
                Object[] userArgs = createdUserIds.toArray();

                try
                {
                    jdbcTemplate.update(
                            "DELETE FROM bookmarks WHERE user_id IN (" + placeholders + ")",
                            userArgs);
                }
                catch (Exception ignored)
                {
                    // Best-effort only.
                }
                try
                {
                    jdbcTemplate.update(
                            "DELETE FROM blocks WHERE blocker_id IN (" + placeholders + ")"
                                    + " OR blocked_id IN (" + placeholders + ")",
                            doubleUserArgs());
                }
                catch (Exception ignored)
                {
                    // Best-effort only.
                }
                try
                {
                    jdbcTemplate.update(
                            "DELETE FROM notifications WHERE recipient_id IN (" + placeholders + ")"
                                    + " OR actor_id IN (" + placeholders + ")",
                            doubleUserArgs());
                }
                catch (Exception ignored)
                {
                    // Best-effort only; user deletion below remains authoritative.
                }

                jdbcTemplate.update(
                        "DELETE FROM sessions WHERE user_id IN (" + placeholders + ")",
                        userArgs);
                jdbcTemplate.update(
                        "DELETE FROM users WHERE id IN (" + placeholders + ")",
                        userArgs);
            }
        }
        catch (Exception e)
        {
            System.err.println("BookmarkIntegrationTest cleanup warning: " + e.getMessage());
        }
    }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    @Test
    void bookmark_thenUnbookmark_roundTripThroughRealDatabase() throws Exception
    {
        AuthResponse author = registerUser("bma");
        AuthResponse reader = registerUser("bmb");

        TweetResponse tweet = createTweet(author, "bookmark-me-1");

        BookmarkResponse bookmark = bookmark(reader, tweet.id());
        assertThat(bookmark.bookmarked()).isTrue();
        assertThat(countBookmarkRows(reader.userId(), tweet.id())).isEqualTo(1L);
        assertThat(isBookmarked(reader.token(), tweet.id()).bookmarked()).isTrue();

        BookmarkResponse unbookmark = unbookmark(reader, tweet.id());
        assertThat(unbookmark.bookmarked()).isFalse();
        assertThat(countBookmarkRows(reader.userId(), tweet.id())).isZero();
        assertThat(isBookmarked(reader.token(), tweet.id()).bookmarked()).isFalse();
    }

    @Test
    void duplicateBookmark_isRejected_andDoesNotCreateSecondRow() throws Exception
    {
        AuthResponse author = registerUser("bmc");
        AuthResponse reader = registerUser("bmd");

        TweetResponse tweet = createTweet(author, "bookmark-me-2");
        bookmark(reader, tweet.id());

        ResponseEnvelope duplicate = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_BOOKMARK,
                gson.toJsonTree(new BookmarkTweetRequest(tweet.id(), reader.token())),
                null));
        assertThat(duplicate.isSuccess()).isFalse();
        assertThat(duplicate.errorCode()).isEqualTo("TWEET_BOOKMARK_FAILED");
        assertThat(duplicate.errorMessage()).containsIgnoringCase("already exists");

        assertThat(countBookmarkRows(reader.userId(), tweet.id())).isEqualTo(1L);
    }

    @Test
    void unbookmark_withoutExistingBookmark_fails() throws Exception
    {
        AuthResponse author = registerUser("bme");
        AuthResponse reader = registerUser("bmf");

        TweetResponse tweet = createTweet(author, "bookmark-me-3");

        ResponseEnvelope response = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_UNBOOKMARK,
                gson.toJsonTree(new UnbookmarkTweetRequest(tweet.id(), reader.token())),
                null));
        assertThat(response.isSuccess()).isFalse();
        assertThat(response.errorCode()).isEqualTo("TWEET_UNBOOKMARK_FAILED");
    }

    @Test
    void bookmark_nonexistentTweet_fails() throws Exception
    {
        AuthResponse reader = registerUser("bmg");

        ResponseEnvelope response = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_BOOKMARK,
                gson.toJsonTree(new BookmarkTweetRequest(
                        UUID.randomUUID(),
                        reader.token())),
                null));
        assertThat(response.isSuccess()).isFalse();
        assertThat(response.errorCode()).isEqualTo("TWEET_BOOKMARK_FAILED");
    }

    @Test
    void unbookmark_removesOnlyOwnBookmark() throws Exception
    {
        AuthResponse author = registerUser("bmh");
        AuthResponse readerA = registerUser("bmi");
        AuthResponse readerB = registerUser("bmj");

        TweetResponse tweet = createTweet(author, "bookmark-me-4");
        bookmark(readerA, tweet.id());
        bookmark(readerB, tweet.id());

        unbookmark(readerA, tweet.id());

        assertThat(countBookmarkRows(readerA.userId(), tweet.id())).isZero();
        assertThat(countBookmarkRows(readerB.userId(), tweet.id()))
                .as("reader B's bookmark must remain untouched")
                .isEqualTo(1L);
    }

    // ========================================================================
    // Block barrier
    // ========================================================================

    @Test
    void blockedAuthor_cannotBeBookmarked() throws Exception
    {
        AuthResponse author = registerUser("bmk");
        AuthResponse reader = registerUser("bml");

        TweetResponse tweet = createTweet(author, "bookmark-me-5");

        // Author blocks reader: the reader can no longer bookmark the tweet.
        block(author, reader.userId());

        ResponseEnvelope response = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_BOOKMARK,
                gson.toJsonTree(new BookmarkTweetRequest(tweet.id(), reader.token())),
                null));
        assertThat(response.isSuccess()).isFalse();
        assertThat(response.errorCode()).isEqualTo("TWEET_BOOKMARK_FAILED");
        assertThat(countBookmarkRows(reader.userId(), tweet.id())).isZero();
    }

    // ========================================================================
    // Private listing
    // ========================================================================

    @Test
    void getBookmarks_returnsOnlyOwnBookmarks_withPagination() throws Exception
    {
        AuthResponse authorA = registerUser("bmm");
        AuthResponse authorB = registerUser("bmn");
        AuthResponse reader = registerUser("bmo");

        TweetResponse t1 = createTweet(authorA, "bm-t1");
        TweetResponse t2 = createTweet(authorB, "bm-t2");
        TweetResponse t3 = createTweet(authorA, "bm-t3");

        bookmark(reader, t1.id());
        bookmark(reader, t2.id());
        bookmark(reader, t3.id());

        GetBookmarksResponse page0 = getBookmarks(reader.token(), 0, 2);
        assertThat(page0.totalItems()).isEqualTo(3);
        assertThat(page0.tweets()).hasSize(2);
        assertThat(page0.hasNext()).isTrue();

        GetBookmarksResponse page1 = getBookmarks(reader.token(), 1, 2);
        assertThat(page1.tweets()).hasSize(1);
        assertThat(page1.hasNext()).isFalse();

        List<UUID> seen = new ArrayList<>();
        page0.tweets().forEach(t -> seen.add(t.tweetId()));
        page1.tweets().forEach(t -> seen.add(t.tweetId()));
        assertThat(seen).containsExactlyInAnyOrder(t1.id(), t2.id(), t3.id());

        // A different user never sees the reader's private bookmarks.
        GetBookmarksResponse other = getBookmarks(authorA.token(), 0, 20);
        assertThat(other.totalItems()).isZero();
        assertThat(other.tweets()).isEmpty();
    }

    @Test
    void getBookmarks_ordersNewestBookmarkFirst() throws Exception
    {
        AuthResponse author = registerUser("bmp");
        AuthResponse reader = registerUser("bmq");

        TweetResponse t1 = createTweet(author, "bm-order-1");
        Thread.sleep(5);
        TweetResponse t2 = createTweet(author, "bm-order-2");
        Thread.sleep(5);
        TweetResponse t3 = createTweet(author, "bm-order-3");

        bookmark(reader, t1.id());
        Thread.sleep(5);
        bookmark(reader, t2.id());
        Thread.sleep(5);
        bookmark(reader, t3.id());

        GetBookmarksResponse response = getBookmarks(reader.token(), 0, 20);
        assertThat(response.tweets()).hasSize(3);
        assertThat(response.tweets().get(0).tweetId()).isEqualTo(t3.id());
        assertThat(response.tweets().get(1).tweetId()).isEqualTo(t2.id());
        assertThat(response.tweets().get(2).tweetId()).isEqualTo(t1.id());
    }

    @Test
    void getBookmarks_respectsDeletedAndBlockedVisibility() throws Exception
    {
        AuthResponse author = registerUser("bmr");
        AuthResponse reader = registerUser("bms");

        TweetResponse deleted = createTweet(author, "bm-vis-1");
        TweetResponse blocked = createTweet(author, "bm-vis-2");

        bookmark(reader, deleted.id());
        bookmark(reader, blocked.id());

        // Delete one tweet: its bookmark disappears from the list.
        deleteTweet(deleted.id(), author.token());
        GetBookmarksResponse afterDelete = getBookmarks(reader.token(), 0, 20);
        assertThat(afterDelete.tweets()).hasSize(1);
        assertThat(afterDelete.tweets().get(0).tweetId()).isEqualTo(blocked.id());

        // Viewer blocks the author: the remaining bookmark becomes invisible.
        block(reader, author.userId());
        GetBookmarksResponse afterBlock = getBookmarks(reader.token(), 0, 20);
        assertThat(afterBlock.totalItems()).isZero();
        assertThat(afterBlock.tweets()).isEmpty();
    }

    @Test
    void getBookmarks_respectsMutedVisibility() throws Exception
    {
        AuthResponse author = registerUser("bmt");
        AuthResponse reader = registerUser("bmu");

        TweetResponse tweet = createTweet(author, "bm-mute-1");
        bookmark(reader, tweet.id());

        // Baseline: the bookmark is listed before muting.
        GetBookmarksResponse before = getBookmarks(reader.token(), 0, 20);
        assertThat(before.totalItems()).isEqualTo(1);
        assertThat(before.tweets().get(0).tweetId()).isEqualTo(tweet.id());

        // Viewer mutes the author: the bookmarked tweet becomes invisible,
        // while the bookmark row itself is untouched (ownership preserved).
        mute(reader, author.userId());

        GetBookmarksResponse after = getBookmarks(reader.token(), 0, 20);
        assertThat(after.totalItems()).isZero();
        assertThat(after.tweets()).isEmpty();
        assertThat(countBookmarkRows(reader.userId(), tweet.id())).isEqualTo(1L);
    }

    // ========================================================================
    // Auth + migration
    // ========================================================================

    @Test
    void bookmark_requiresAuthentication() throws Exception
    {
        MockHttpServletResponse response = mockMvc.perform(post("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gson.toJson(new RequestEnvelope(
                                UUID.randomUUID(),
                                RequestType.TWEET_BOOKMARK,
                                gson.toJsonTree(new BookmarkTweetRequest(
                                        UUID.randomUUID(),
                                        null)),
                                null))))
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse();

        ResponseEnvelope envelope =
                gson.fromJson(response.getContentAsString(), ResponseEnvelope.class);
        assertThat(envelope.isSuccess()).isFalse();
        assertThat(envelope.errorCode()).isEqualTo("AUTH_REQUIRED");
    }

    @Test
    void flywayV2Migration_createdBookmarksTable() throws Exception
    {
        Long applied = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '2' AND success = true",
                Long.class);
        assertThat(applied)
                .as("Flyway V2 (bookmarks) migration must be applied")
                .isEqualTo(1L);

        Long tableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = 'bookmarks'",
                Long.class);
        assertThat(tableCount).isEqualTo(1L);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private AuthResponse registerUser(String prefix) throws Exception
    {
        String username = prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest registerRequest = new RegisterRequest(
                username,
                username + "@bookmarktest.com",
                "StrongPassword123!",
                "Display " + prefix);

        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.AUTH_REGISTER,
                gson.toJsonTree(registerRequest),
                null));
        assertSuccess(envelope, "register " + username);

        AuthResponse auth = gson.fromJson(envelope.getData(), AuthResponse.class);
        createdUserIds.add(auth.userId());
        return auth;
    }

    private TweetResponse createTweet(AuthResponse author, String content) throws Exception
    {
        CreateTweetRequest createRequest =
                new CreateTweetRequest(content, null, null, null, author.token(), null);
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_CREATE,
                gson.toJsonTree(createRequest),
                null));
        assertSuccess(envelope, "create tweet " + content);

        TweetResponse tweet = gson.fromJson(envelope.getData(), TweetResponse.class);
        createdTweetIds.add(tweet.id());
        return tweet;
    }

    private void deleteTweet(UUID tweetId, String token) throws Exception
    {
        DeleteTweetRequest deleteRequest = new DeleteTweetRequest(tweetId, token);
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_DELETE,
                gson.toJsonTree(deleteRequest),
                null));
        assertSuccess(envelope, "delete tweet " + tweetId);
    }

    private BookmarkResponse bookmark(AuthResponse reader, UUID tweetId) throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_BOOKMARK,
                gson.toJsonTree(new BookmarkTweetRequest(tweetId, reader.token())),
                null));
        assertSuccess(envelope, "bookmark " + tweetId);
        return gson.fromJson(envelope.getData(), BookmarkResponse.class);
    }

    private BookmarkResponse unbookmark(AuthResponse reader, UUID tweetId) throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_UNBOOKMARK,
                gson.toJsonTree(new UnbookmarkTweetRequest(tweetId, reader.token())),
                null));
        assertSuccess(envelope, "unbookmark " + tweetId);
        return gson.fromJson(envelope.getData(), BookmarkResponse.class);
    }

    private GetIsBookmarkedResponse isBookmarked(String token, UUID tweetId) throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.USER_GET_IS_BOOKMARKED,
                gson.toJsonTree(GetIsBookmarkedRequest.builder()
                        .sessionToken(token)
                        .tweetId(tweetId)
                        .build()),
                null));
        assertSuccess(envelope, "is-bookmarked check for " + tweetId);
        return gson.fromJson(envelope.getData(), GetIsBookmarkedResponse.class);
    }

    private GetBookmarksResponse getBookmarks(String token, int page, int pageSize)
            throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.BOOKMARKS_GET,
                gson.toJsonTree(new GetBookmarksRequest(page, pageSize, token)),
                null));
        assertSuccess(envelope, "get bookmarks");
        return gson.fromJson(envelope.getData(), GetBookmarksResponse.class);
    }

    private void block(AuthResponse blocker, UUID blockedId) throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.RELATION_BLOCK,
                gson.toJsonTree(new BlockUserRequest(blockedId, blocker.token())),
                null));
        assertSuccess(envelope, "block " + blockedId);
    }

    private void mute(AuthResponse muter, UUID mutedId) throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.RELATION_MUTE,
                gson.toJsonTree(new MuteUserRequest(mutedId, muter.token())),
                null));
        assertSuccess(envelope, "mute " + mutedId);
    }

    private long countBookmarkRows(UUID userId, UUID tweetId)
    {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookmarks WHERE user_id = ? AND tweet_id = ?",
                Long.class,
                userId,
                tweetId);
        return count == null ? 0L : count;
    }

    private ResponseEnvelope send(RequestEnvelope request) throws Exception
    {
        MockHttpServletResponse response = mockMvc.perform(post("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gson.toJson(request)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();

        ResponseEnvelope envelope = gson.fromJson(response.getContentAsString(), ResponseEnvelope.class);
        assertThat(envelope).isNotNull();
        return envelope;
    }

    private Object[] doubleUserArgs()
    {
        Object[] userArgs = createdUserIds.toArray();
        Object[] doubleArgs = new Object[createdUserIds.size() * 2];
        System.arraycopy(userArgs, 0, doubleArgs, 0, createdUserIds.size());
        System.arraycopy(userArgs, 0, doubleArgs, createdUserIds.size(), createdUserIds.size());
        return doubleArgs;
    }

    private void assertSuccess(ResponseEnvelope envelope, String step)
    {
        assertThat(envelope.isSuccess())
                .as("%s — errorCode=%s, errorMessage=%s", step, envelope.errorCode(), envelope.errorMessage())
                .isTrue();
    }

    private static String repeatPlaceholders(int count)
    {
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < count; i++)
        {
            if (i > 0)
            {
                placeholders.append(",");
            }
            placeholders.append("?");
        }
        return placeholders.toString();
    }

    private static int findFreePort()
    {
        try (ServerSocket socket = new ServerSocket(0))
        {
            return socket.getLocalPort();
        }
        catch (Exception e)
        {
            throw new IllegalStateException("Could not find a free TCP port", e);
        }
    }
}