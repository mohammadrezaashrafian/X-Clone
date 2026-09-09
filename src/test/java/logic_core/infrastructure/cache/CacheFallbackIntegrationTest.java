package logic_core.infrastructure.cache;

import com.google.gson.Gson;
import logic_core.app.dto.request.CreateTweetRequest;
import logic_core.app.dto.request.GetProfileRequest;
import logic_core.app.dto.request.GetTrendingHashtagsRequest;
import logic_core.app.dto.request.GetTweetRequest;
import logic_core.app.dto.request.RegisterRequest;
import logic_core.app.dto.response.AuthResponse;
import logic_core.app.dto.response.ProfileInfoResponse;
import logic_core.app.dto.response.TrendingHashtagsResponse;
import logic_core.app.dto.response.TweetResponse;
import logic_core.app.dto.timeline.TimelineTweet;
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
 * Redis-unavailable fallback (V2.1 #19).
 *
 * <p>Cache is enabled ({@code app.cache.enabled=true}) but points at a dead
 * Redis port with a short connect timeout. Every cache-backed read must still
 * succeed from PostgreSQL — Redis failures degrade to misses/no-ops and are
 * counted in {@link CacheMetrics#errors()}. Authoritative data stays correct.
 */
@SpringBootTest(classes = ServerMain.class)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CacheFallbackIntegrationTest
{
    /**
     * Dead Redis port: nothing listens here on this machine (no local Redis,
     * embedded server is deliberately NOT started for this class).
     */
    private static final int DEAD_REDIS_PORT = 6399;

    private static final int TEST_SOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerTestProperties(DynamicPropertyRegistry registry)
    {
        registry.add("server.socket.port", () -> TEST_SOCKET_PORT);
        registry.add("app.cache.enabled", () -> "true");
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> String.valueOf(DEAD_REDIS_PORT));
        registry.add("spring.data.redis.connect-timeout", () -> "300ms");
        registry.add("spring.data.redis.timeout", () -> "500ms");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Gson gson;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CacheMetrics cacheMetrics;

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
                jdbcTemplate.update(
                        "DELETE FROM tweets WHERE id IN (" + tweetPlaceholders + ")",
                        tweetArgs);
            }
            if (!createdUserIds.isEmpty())
            {
                String placeholders = repeatPlaceholders(createdUserIds.size());
                Object[] userArgs = createdUserIds.toArray();
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
            System.err.println("CacheFallbackIntegrationTest cleanup warning: " + e.getMessage());
        }
    }

    @Test
    void profileRead_fallsBackToPostgresql_whenRedisIsUnavailable() throws Exception
    {
        AuthResponse user = registerUser("falla");

        long errorsBefore = cacheMetrics.errors();

        ProfileInfoResponse profile = getProfile(user.userId(), user.token());

        assertThat(profile.displayName()).isEqualTo("Display falla");
        assertThat(profile.username()).isNotNull();

        // Every cache operation failed and was recorded — but the read succeeded.
        assertThat(cacheMetrics.errors())
                .as("Redis connection failures recorded by cache metrics")
                .isGreaterThan(errorsBefore);
    }

    @Test
    void tweetAndTrendingReads_fallBackToPostgresql_whenRedisIsUnavailable() throws Exception
    {
        AuthResponse user = registerUser("fallb");

        TweetResponse tweet = createTweet(user, "fallback fixture #falltag");

        TimelineTweet fetched = getTweet(tweet.id(), user.token());
        assertThat(fetched.content()).isEqualTo("fallback fixture #falltag");

        long errorsBefore = cacheMetrics.errors();

        TrendingHashtagsResponse trending = getTrending(50, user.token());

        assertThat(trending.items())
                .filteredOn(i -> i.tag().equals("falltag"))
                .isNotEmpty();

        assertThat(cacheMetrics.errors())
                .as("Redis connection failures recorded by cache metrics")
                .isGreaterThan(errorsBefore);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private AuthResponse registerUser(String prefix) throws Exception
    {
        String username = prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest registerRequest = new RegisterRequest(
                username,
                username + "@cachefallbacktest.com",
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

    private ProfileInfoResponse getProfile(UUID userId, String token) throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.USER_GET_PROFILE,
                gson.toJsonTree(new GetProfileRequest(token, userId)),
                null));
        assertSuccess(envelope, "get profile " + userId);
        return gson.fromJson(envelope.getData(), ProfileInfoResponse.class);
    }

    private TimelineTweet getTweet(UUID tweetId, String token) throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_GET,
                gson.toJsonTree(new GetTweetRequest(tweetId, token)),
                null));
        assertSuccess(envelope, "get tweet " + tweetId);
        return gson.fromJson(envelope.getData(), TimelineTweet.class);
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