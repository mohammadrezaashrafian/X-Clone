package logic_core.infrastructure.transport.http;

import com.google.gson.Gson;
import logic_core.app.dto.request.CreateTweetRequest;
import logic_core.app.dto.request.DeleteTweetRequest;
import logic_core.app.dto.request.GetHashtagTweetsRequest;
import logic_core.app.dto.request.GetNotificationsRequest;
import logic_core.app.dto.request.GetTrendingHashtagsRequest;
import logic_core.app.dto.request.RegisterRequest;
import logic_core.app.dto.request.SearchHashtagsRequest;
import logic_core.app.dto.request.SearchTweetsRequest;
import logic_core.app.dto.response.AuthResponse;
import logic_core.app.dto.response.GetNotificationsPageResponse;
import logic_core.app.dto.response.HashtagSearchResponse;
import logic_core.app.dto.response.HashtagTweetsResponse;
import logic_core.app.dto.response.NotificationResponse;
import logic_core.app.dto.response.TrendingHashtagsResponse;
import logic_core.app.dto.response.TweetResponse;
import logic_core.app.dto.response.TweetSearchResponse;
import logic_core.domain.model.notification.NotificationType;
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
 * Milestone-level cross-feature integration suite ([V2.1 #10]).
 *
 * <p>Each focused V2.1 feature has its own integration test; this suite
 * exercises the realistic interactions between already-tested features
 * through the full stack ({@code POST /api} → {@code RequestDispatcher} →
 * Facade → UseCase → Repository Adapter → JPA → PostgreSQL):
 *
 * <ul>
 *   <li>{@link #tweetWithHashtagAndMention_isSearchable_trending_andNotifies()}
 *       — one tweet creation fans out to hashtag persistence, mention
 *       persistence, a MENTION notification, hashtag search, tweet search
 *       and the trending ranking.</li>
 *   <li>{@link #softDeletedTweet_keepsMentionNotification_butLeavesAllReads()}
 *       — soft-deleting a tweet keeps the mention notification retrievable
 *       while removing the tweet from every read surface (search, hashtag
 *       feed, trending).</li>
 * </ul>
 *
 * <p>Uses the same real-PostgreSQL harness as the other HTTP integration
 * tests; every row created here is removed in {@link #cleanUpCreatedRows()}.
 */
@SpringBootTest(classes = ServerMain.class)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CrossFeatureIntegrationTest
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
                String tweetPlaceholders = repeatPlaceholders(createdTweetIds.size());
                Object[] tweetArgs = createdTweetIds.toArray();

                try
                {
                    jdbcTemplate.update(
                            "DELETE FROM hashtags WHERE id IN ("
                                    + " SELECT hashtag_id FROM tweet_hashtags WHERE tweet_id IN ("
                                    + tweetPlaceholders + "))",
                            tweetArgs);
                }
                catch (Exception ignored)
                {
                    // Best-effort only.
                }
                try
                {
                    jdbcTemplate.update(
                            "DELETE FROM tweet_mentions WHERE tweet_id IN ("
                                    + tweetPlaceholders + ")",
                            tweetArgs);
                }
                catch (Exception ignored)
                {
                    // Best-effort only.
                }
                jdbcTemplate.update(
                        "DELETE FROM tweets WHERE id IN (" + tweetPlaceholders + ")",
                        tweetArgs);
            }
            if (!createdUserIds.isEmpty())
            {
                String placeholders = repeatPlaceholders(createdUserIds.size());
                Object[] userArgs = createdUserIds.toArray();

                try
                {
                    jdbcTemplate.update(
                            "DELETE FROM notifications WHERE recipient_id IN ("
                                    + placeholders + ")"
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
            System.err.println("CrossFeatureIntegrationTest cleanup warning: " + e.getMessage());
        }
    }

    // ========================================================================
    // Tweet creation fans out: hashtags + mentions + notification + search +
    // trending
    // ========================================================================

    @Test
    void tweetWithHashtagAndMention_isSearchable_trending_andNotifies() throws Exception
    {
        AuthResponse author = registerUser("cfa");
        AuthResponse mentioned = registerUser("cfb");
        AuthResponse viewer = registerUser("cfc");

        String tag = "crosstag" + UUID.randomUUID().toString().substring(0, 6);
        TweetResponse tweet = createTweet(
                author,
                "hello #" + tag + " ping @" + mentioned.username());

        // 1. Hashtag + mention persisted through the real tweet-creation flow.
        assertThat(countTweetHashtagRows(tweet.id(), tag)).isEqualTo(1);
        assertThat(mentionUserIds(tweet.id())).containsExactly(mentioned.userId());

        // 2. MENTION notification created for the mentioned user.
        List<NotificationResponse> notifications =
                getNotifications(mentioned).notifications();
        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0).type()).isEqualTo(NotificationType.MENTION);
        assertThat(notifications.get(0).tweetId()).isEqualTo(tweet.id());
        assertThat(notifications.get(0).read()).isFalse();

        // 3. Hashtag search finds the persisted tag.
        HashtagSearchResponse hashtagSearch = searchHashtags(tag, 0, 20, viewer.token());
        assertThat(hashtagSearch.hashtags())
                .extracting(HashtagSearchResponse.HashtagSearchItem::tag)
                .contains(tag);

        // 4. Tweet search finds the tweet by its content.
        TweetSearchResponse tweetSearch = searchTweets(tag, 0, 20, viewer.token());
        assertThat(tweetSearch.totalItems()).isEqualTo(1);
        assertThat(tweetSearch.tweets().get(0).tweetId()).isEqualTo(tweet.id());

        // 5. Trending includes the hashtag (published now => inside the 24h
        // window).
        TrendingHashtagsResponse trending = getTrending(50, viewer.token());
        assertThat(trending.items())
                .filteredOn(i -> i.tag().equals(tag))
                .isNotEmpty();
    }

    // ========================================================================
    // Tweet soft-delete: notification survives, every read surface drops the
    // tweet
    // ========================================================================

    @Test
    void softDeletedTweet_keepsMentionNotification_butLeavesAllReads() throws Exception
    {
        AuthResponse author = registerUser("cfd");
        AuthResponse mentioned = registerUser("cfe");
        AuthResponse viewer = registerUser("cff");

        String tag = "crosstag" + UUID.randomUUID().toString().substring(0, 6);
        TweetResponse tweet = createTweet(
                author,
                "shoutout #" + tag + " @" + mentioned.username());

        // Notification exists while the tweet is active.
        assertThat(getNotifications(mentioned).notifications()).hasSize(1);

        // Soft-delete the tweet through the real flow.
        deleteTweet(tweet.id(), author.token());

        // The mention notification survives soft deletion: notifications are
        // only removed by undoing the interaction or hard-deleting the tweet
        // row (tweet_id ON DELETE CASCADE); a soft delete keeps the row and
        // notification retrieval does not filter deleted tweets.
        List<NotificationResponse> after =
                getNotifications(mentioned).notifications();
        assertThat(after).hasSize(1);
        assertThat(after.get(0).type()).isEqualTo(NotificationType.MENTION);
        assertThat(after.get(0).tweetId()).isEqualTo(tweet.id());

        // The tweet leaves every read surface.
        assertThat(searchTweets(tag, 0, 20, viewer.token()).totalItems()).isZero();

        HashtagTweetsResponse feed = getHashtagTweets(tag, 0, 20, viewer.token());
        assertThat(feed.totalItems()).isZero();
        assertThat(feed.tweets()).isEmpty();

        TrendingHashtagsResponse trending = getTrending(50, viewer.token());
        assertThat(trending.items())
                .filteredOn(i -> i.tag().equals(tag))
                .isEmpty();

        // The persisted hashtag row itself survives soft deletion (only the
        // tweet disappears from the feed); hashtag search operates on the
        // persisted tag table, so the tag remains discoverable.
        HashtagSearchResponse hashtagSearch = searchHashtags(tag, 0, 20, viewer.token());
        assertThat(hashtagSearch.hashtags())
                .extracting(HashtagSearchResponse.HashtagSearchItem::tag)
                .contains(tag);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private AuthResponse registerUser(String prefix) throws Exception
    {
        String username = prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest registerRequest = new RegisterRequest(
                username,
                username + "@crossfeaturetest.com",
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

    private GetNotificationsPageResponse getNotifications(AuthResponse user) throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.NOTIFICATION_GET,
                gson.toJsonTree(new GetNotificationsRequest(user.token(), 0, 20)),
                null));
        assertSuccess(envelope, "get notifications");
        return gson.fromJson(envelope.getData(), GetNotificationsPageResponse.class);
    }

    private TweetSearchResponse searchTweets(
            String query, int page, int pageSize, String token) throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_SEARCH,
                gson.toJsonTree(new SearchTweetsRequest(query, page, pageSize, token)),
                null));
        assertSuccess(envelope, "tweet search " + query);
        return gson.fromJson(envelope.getData(), TweetSearchResponse.class);
    }

    private HashtagSearchResponse searchHashtags(
            String query, int page, int pageSize, String token) throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.HASHTAG_SEARCH,
                gson.toJsonTree(new SearchHashtagsRequest(query, page, pageSize, token)),
                null));
        assertSuccess(envelope, "hashtag search " + query);
        return gson.fromJson(envelope.getData(), HashtagSearchResponse.class);
    }

    private HashtagTweetsResponse getHashtagTweets(
            String tag, int page, int pageSize, String token) throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.HASHTAG_GET_TWEETS,
                gson.toJsonTree(new GetHashtagTweetsRequest(tag, page, pageSize, token)),
                null));
        assertSuccess(envelope, "hashtag feed " + tag);
        return gson.fromJson(envelope.getData(), HashtagTweetsResponse.class);
    }

    private TrendingHashtagsResponse getTrending(int limit, String token) throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TRENDING_HASHTAGS,
                gson.toJsonTree(new GetTrendingHashtagsRequest(token, limit)),
                null));
        assertSuccess(envelope, "trending hashtags");
        return gson.fromJson(envelope.getData(), TrendingHashtagsResponse.class);
    }

    private int countTweetHashtagRows(UUID tweetId, String tag)
    {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tweet_hashtags th"
                        + " JOIN hashtags h ON h.id = th.hashtag_id"
                        + " WHERE th.tweet_id = ? AND h.tag = ?",
                Integer.class,
                tweetId,
                tag);
        return count == null ? 0 : count;
    }

    private List<UUID> mentionUserIds(UUID tweetId)
    {
        return jdbcTemplate.queryForList(
                "SELECT mentioned_user_id FROM tweet_mentions WHERE tweet_id = ?",
                UUID.class,
                tweetId);
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