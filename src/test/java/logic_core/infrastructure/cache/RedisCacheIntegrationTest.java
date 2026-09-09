package logic_core.infrastructure.cache;

import com.google.gson.Gson;
import logic_core.app.dto.request.CreateTweetRequest;
import logic_core.app.dto.request.DeleteTweetRequest;
import logic_core.app.dto.request.EditTweetRequest;
import logic_core.app.dto.request.GetProfileRequest;
import logic_core.app.dto.request.GetTrendingHashtagsRequest;
import logic_core.app.dto.request.GetTweetRequest;
import logic_core.app.dto.request.RegisterRequest;
import logic_core.app.dto.request.UpdateBioRequest;
import logic_core.app.dto.response.AuthResponse;
import logic_core.app.dto.response.ProfileInfoResponse;
import logic_core.app.dto.response.TrendingHashtagsResponse;
import logic_core.app.dto.response.TweetResponse;
import logic_core.app.dto.timeline.TimelineTweet;
import logic_core.infrastructure.transport.RequestEnvelope;
import logic_core.infrastructure.transport.RequestType;
import logic_core.infrastructure.transport.ResponseEnvelope;
import logic_core.infrastructure.transport.server.ServerMain;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import redis.embedded.RedisServer;

import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Redis cache behavior — full stack integration (V2.1 #19).
 *
 * <p>Brings up a <b>real Redis server</b> (embedded Redis binary, no Docker
 * required) and drives the full application path ({@code POST /api} →
 * {@code RequestDispatcher} → Facade → UseCase → cache/→ JPA → PostgreSQL)
 * for the three cached routes: {@code USER_GET_PROFILE}, {@code TWEET_GET}
 * and {@code TRENDING_HASHTAGS}.
 *
 * <p>Connection settings are plain {@code spring.data.redis.*} properties set
 * via {@link DynamicPropertySource}, so migrating these tests to Testcontainers
 * later only replaces the server bootstrap — the tests themselves stay intact.
 *
 * <p>Because trending is a global aggregate over the shared database,
 * assertions filter on this test's own {@code cacht-} tagged fixtures.
 */
@SpringBootTest(classes = ServerMain.class)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisCacheIntegrationTest
{
    private static final int TEST_SOCKET_PORT = findFreePort();
    private static final int REDIS_PORT = findFreePort();

    private static final RedisServer REDIS_SERVER;
    static
    {
        RedisServer server;
        try
        {
            server = new RedisServer(REDIS_PORT);
            server.start();
        }
        catch (Exception e)
        {
            throw new IllegalStateException("Could not start embedded Redis on port " + REDIS_PORT, e);
        }
        REDIS_SERVER = server;
    }

    @DynamicPropertySource
    static void registerTestProperties(DynamicPropertyRegistry registry)
    {
        registry.add("server.socket.port", () -> TEST_SOCKET_PORT);
        registry.add("app.cache.enabled", () -> "true");
        registry.add("spring.data.redis.port", () -> String.valueOf(REDIS_PORT));
        registry.add("spring.data.redis.host", () -> "localhost");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Gson gson;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private CacheMetrics cacheMetrics;

    private final List<UUID> createdTweetIds = new ArrayList<>();
    private final List<UUID> createdUserIds = new ArrayList<>();

    @BeforeEach
    void clearCacheAndResetFixtureTracking()
    {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        createdTweetIds.clear();
        createdUserIds.clear();
    }

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
                            "DELETE FROM likes WHERE tweet_id IN (" + tweetPlaceholders + ")",
                            tweetArgs);
                }
                catch (Exception ignored)
                {
                    // Best-effort only.
                }
                try
                {
                    jdbcTemplate.update(
                            "DELETE FROM tweet_edits WHERE tweet_id IN (" + tweetPlaceholders + ")",
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
                            "DELETE FROM hashtag_follows WHERE user_id IN (" + placeholders + ")",
                            userArgs);
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
                    // Best-effort only.
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
            System.err.println("RedisCacheIntegrationTest cleanup warning: " + e.getMessage());
        }
    }

    @AfterAll
    static void stopRedis()
    {
        try
        {
            REDIS_SERVER.stop();
        }
        catch (Exception ignored)
        {
            // Best-effort shutdown.
        }
    }

    // ========================================================================
    // Profile cache
    // ========================================================================

    @Test
    void profileRead_populatesCache_andBioUpdateInvalidatesIt() throws Exception
    {
        AuthResponse user = registerUser("cacp");
        String cacheKey = cacheKeyForProfile(user.userId());

        ProfileInfoResponse first = getProfile(user.userId(), user.token());
        assertThat(first.displayName()).isEqualTo("Display cacp");
        assertThat(redisTemplate.hasKey(cacheKey)).as("profile cached after first read").isTrue();

        long hitsBefore = cacheMetrics.hits();

        ProfileInfoResponse second = getProfile(user.userId(), user.token());
        assertThat(second.displayName()).isEqualTo("Display cacp");
        assertThat(cacheMetrics.hits()).as("second read served from cache").isGreaterThan(hitsBefore);

        // Mutate the profile through the real route.
        updateBio(user.userId(), "brand new bio", user.token());

        assertThat(redisTemplate.hasKey(cacheKey))
                .as("profile cache invalidated after bio update")
                .isFalse();

        ProfileInfoResponse after = getProfile(user.userId(), user.token());
        assertThat(after.bio()).as("fresh value loaded from PostgreSQL").isEqualTo("brand new bio");
    }

    @Test
    void profileCache_entryCarriesBoundedTtl() throws Exception
    {
        AuthResponse user = registerUser("cact");

        getProfile(user.userId(), user.token());

        Long ttlSeconds = redisTemplate.getExpire(
                cacheKeyForProfile(user.userId()), TimeUnit.SECONDS);

        assertThat(ttlSeconds)
                .as("cached profile must expire within the configured 60s TTL")
                .isNotNull()
                .isGreaterThan(0)
                .isLessThanOrEqualTo(60);
    }

    // ========================================================================
    // Single-tweet cache
    // ========================================================================

    @Test
    void tweetRead_populatesCache_editAndDeleteInvalidateAllViewers() throws Exception
    {
        AuthResponse author = registerUser("caca");
        AuthResponse viewer = registerUser("cacb");

        TweetResponse tweet = createTweet(author, "original cached content");

        // Both actors read the tweet -> two actor-scoped entries.
        TimelineTweet authorView = getTweet(tweet.id(), author.token());
        assertThat(authorView.content()).isEqualTo("original cached content");
        getTweet(tweet.id(), viewer.token());

        assertThat(redisTemplate.hasKey(cacheKeyForTweet(author.userId(), tweet.id())))
                .as("author's cached entry exists").isTrue();
        assertThat(redisTemplate.hasKey(cacheKeyForTweet(viewer.userId(), tweet.id())))
                .as("viewer's cached entry exists").isTrue();

        // Edit invalidates entries for ALL viewers.
        editTweet(tweet.id(), "edited cached content", author.token());

        assertThat(redisTemplate.hasKey(cacheKeyForTweet(author.userId(), tweet.id())))
                .as("author's entry evicted after edit").isFalse();
        assertThat(redisTemplate.hasKey(cacheKeyForTweet(viewer.userId(), tweet.id())))
                .as("viewer's entry evicted after edit").isFalse();

        // A fresh read reflects the edited content and repopulates the cache.
        TimelineTweet afterEdit = getTweet(tweet.id(), author.token());
        assertThat(afterEdit.content()).isEqualTo("edited cached content");
        assertThat(redisTemplate.hasKey(cacheKeyForTweet(author.userId(), tweet.id())))
                .as("fresh entry repopulated after edit").isTrue();

        // Delete invalidates all viewer entries again.
        deleteTweet(tweet.id(), author.token());

        assertThat(redisTemplate.hasKey(cacheKeyForTweet(author.userId(), tweet.id())))
                .as("author's entry evicted after delete").isFalse();
        assertThat(redisTemplate.hasKey(cacheKeyForTweet(viewer.userId(), tweet.id())))
                .as("viewer's entry evicted after delete").isFalse();
    }

    @Test
    void tweet_readOfInaccessibleTweetIsNeverCached() throws Exception
    {
        // Create user A and have A delete their own tweet so reads fail;
        // failed/not-found loads must not be cached (no negative caching).
        AuthResponse author = registerUser("cacc");

        TweetResponse tweet = createTweet(author, "will vanish");
        deleteTweet(tweet.id(), author.token());

        long errorsBefore = cacheMetrics.errors();

        getTweetExpectingFailure(tweet.id(), author.token());

        assertThat(redisTemplate.hasKey(cacheKeyForTweet(author.userId(), tweet.id())))
                .as("failed tweet load must not populate the cache")
                .isFalse();
        assertThat(cacheMetrics.errors()).as("no redis errors (failure came from app layer)")
                .isEqualTo(errorsBefore);
    }

    // ========================================================================
    // Trending cache
    // ========================================================================

    @Test
    void trendingRead_populatesCache_andServesConsistentResults() throws Exception
    {
        AuthResponse author = registerUser("cacd");

        createTweet(author, "trending fixture #cacht1");
        createTweet(author, "trending fixture #cacht1");
        createTweet(author, "trending fixture #cacht2");

        String cacheKey = "xc:trending:hashtags:50";

        TrendingHashtagsResponse first = getTrending(50, author.token());

        assertThat(first.items())
                .filteredOn(i -> i.tag().equals("cacht1"))
                .first()
                .extracting(TrendingHashtagsResponse.TrendingHashtagItem::score)
                .isEqualTo(2L);
        assertThat(first.items())
                .filteredOn(i -> i.tag().equals("cacht2"))
                .first()
                .extracting(TrendingHashtagsResponse.TrendingHashtagItem::score)
                .isEqualTo(1L);

        assertThat(redisTemplate.hasKey(cacheKey)).as("trending cached after first read").isTrue();

        long hitsBefore = cacheMetrics.hits();
        TrendingHashtagsResponse second = getTrending(50, author.token());

        assertThat(cacheMetrics.hits()).as("second trending read served from cache").isGreaterThan(hitsBefore);
        assertThat(second.items()).usingRecursiveFieldByFieldElementComparator().isEqualTo(first.items());
    }

    // ========================================================================
    // Performance baseline (basic, non-scientific)
    // ========================================================================

    @Test
    void performanceBaseline_warmProfileReadsAreFasterThanCold() throws Exception
    {
        AuthResponse user = registerUser("cace");
        String cacheKey = cacheKeyForProfile(user.userId());

        // Warm the connection/context first (outside the measurement).
        getProfile(user.userId(), user.token());

        double coldMs = medianOf(3, () -> measuredProfileReadMs(cacheKey, user, true));
        double warmMs = medianOf(3, () -> measuredProfileReadMs(cacheKey, user, false));

        System.out.printf(
                "[perf] profile read — cold median %.2f ms, warm median %.2f ms%n",
                coldMs, warmMs);

        // Cache hits are a Redis GET + decode; cold includes the DB round trip.
        // The margin is huge, so an order-of-magnitude assertion is safe.
        assertThat(warmMs)
                .as("warm (cached) profile read must be faster than cold (DB) read")
                .isLessThan(coldMs);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private String cacheKeyForProfile(UUID userId)
    {
        return "xc:user:profile:" + userId;
    }

    private String cacheKeyForTweet(UUID actorId, UUID tweetId)
    {
        return "xc:tweet:" + actorId + ":" + tweetId;
    }

    private double measuredProfileReadMs(String cacheKey, AuthResponse user, boolean evictFirst)
    {
        try
        {
            if (evictFirst)
            {
                redisTemplate.delete(cacheKey);
            }
            long start = System.nanoTime();
            getProfile(user.userId(), user.token());
            return (System.nanoTime() - start) / 1_000_000.0;
        }
        catch (Exception e)
        {
            throw new RuntimeException("performance measurement failed", e);
        }
    }

    private double medianOf(int runs, DoubleSupplier action)
    {
        List<Double> samples = new ArrayList<>();
        for (int i = 0; i < runs; i++)
        {
            samples.add(action.getAsDouble());
        }
        samples.sort(Double::compareTo);
        return samples.get(samples.size() / 2);
    }

    private AuthResponse registerUser(String prefix) throws Exception
    {
        String username = prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest registerRequest = new RegisterRequest(
                username,
                username + "@rediscachetest.com",
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

    private void updateBio(UUID userId, String bio, String token) throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.USER_UPDATE_BIO,
                gson.toJsonTree(new UpdateBioRequest(token, bio)),
                null));
        assertSuccess(envelope, "update bio for " + userId);
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

    private void getTweetExpectingFailure(UUID tweetId, String token) throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_GET,
                gson.toJsonTree(new GetTweetRequest(tweetId, token)),
                null));
        assertThat(envelope.isSuccess()).as("tweet read must fail").isFalse();
    }

    private void editTweet(UUID tweetId, String content, String token) throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_EDIT,
                gson.toJsonTree(new EditTweetRequest(tweetId, content, token)),
                null));
        assertSuccess(envelope, "edit tweet " + tweetId);
    }

    private void deleteTweet(UUID tweetId, String token) throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_DELETE,
                gson.toJsonTree(new DeleteTweetRequest(tweetId, token)),
                null));
        assertSuccess(envelope, "delete tweet " + tweetId);
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