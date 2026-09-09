package logic_core.infrastructure.transport.http;

import com.google.gson.Gson;
import logic_core.app.dto.request.CreateTweetRequest;
import logic_core.app.dto.request.DeleteTweetRequest;
import logic_core.app.dto.request.GetTrendingHashtagsRequest;
import logic_core.app.dto.request.RegisterRequest;
import logic_core.app.dto.request.RetweetRequest;
import logic_core.app.dto.response.AuthResponse;
import logic_core.app.dto.response.TrendingHashtagsResponse;
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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Core integration suite — trending hashtags ([V2.1 #9] Trending Foundation).
 *
 * <p>Drives the full stack ({@code POST /api} → {@code RequestDispatcher} →
 * {@code HashtagFacade} → {@code GetTrendingHashtagsUseCase} →
 * {@code HashtagRepositoryAdapter} → JPA → PostgreSQL) for the
 * {@code TRENDING_HASHTAGS} route and verifies the documented ranking
 * contract against real persisted data:
 *
 * <ul>
 *   <li><b>Ranking formula</b> — number of distinct qualifying tweets per
 *       hashtag inside the window, descending (in-tweet {@code usage_count}
 *       repetition does not inflate the score).</li>
 *   <li><b>Supported window</b> — trailing 24 hours, half-open
 *       {@code [windowStart, windowEnd)} on {@code tweets.published_at}.
 *       The use case computes both boundaries from the clock at query time,
 *       so the boundary fixtures below use ±60s margins, which are
 *       deterministic for any test execution: a tweet at
 *       {@code now - 24h + 60s} is always inside the window (the query runs
 *       within seconds of fixture creation), a tweet at
 *       {@code now - 24h - 60s} is always before the window start, and a
 *       tweet at {@code now + 60s} is always at/after the exclusive window
 *       end.</li>
 *   <li><b>Tie-breaker</b> — equal scores order by canonical tag ascending.</li>
 *   <li><b>Lifecycle</b> — soft-deleted tweets, deleted authors and retweet
 *       markers never contribute.</li>
 * </ul>
 *
 * <p>Uses the same real-PostgreSQL harness as the other HTTP integration
 * tests ({@code xclonedb}, Flyway-baselined schema) and removes every row it
 * creates in {@link #cleanUpCreatedRows()}. Because trending is a global
 * aggregate over the shared database, assertions on ranking are expressed
 * relative to this test's own fixtures (their scores are made large enough
 * to dominate incidental rows).
 */
@SpringBootTest(classes = ServerMain.class)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TrendingHashtagsIntegrationTest
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
            System.err.println("TrendingHashtagsIntegrationTest cleanup warning: " + e.getMessage());
        }
    }

    // ========================================================================
    // Basic ranking (distinct-tweet counting, descending order, ranks)
    // ========================================================================

    @Test
    void trending_ranksHashtagsByDistinctTweetCount_descending() throws Exception
    {
        AuthResponse author = registerUser("tnda");

        // tagHigh: 3 distinct tweets, tagMid: 2, tagLow: 1. Scores far exceed
        // any incidental rows in the shared database.
        insertTaggedTweet(author.userId(), "trendhigh", OffsetDateTime.now().minusHours(1));
        insertTaggedTweet(author.userId(), "trendhigh", OffsetDateTime.now().minusHours(2));
        insertTaggedTweet(author.userId(), "trendhigh", OffsetDateTime.now().minusHours(3));
        insertTaggedTweet(author.userId(), "trendmid", OffsetDateTime.now().minusHours(1));
        insertTaggedTweet(author.userId(), "trendmid", OffsetDateTime.now().minusHours(2));
        insertTaggedTweet(author.userId(), "trendlow", OffsetDateTime.now().minusHours(1));

        TrendingHashtagsResponse response = getTrending(50, author.token());

        List<String> ourTagsInOrder = response.items().stream()
                .map(TrendingHashtagsResponse.TrendingHashtagItem::tag)
                .filter(tag -> tag.startsWith("trend"))
                .toList();

        assertThat(ourTagsInOrder)
                .as("descending score order: high(3), mid(2), low(1)")
                .containsExactly("trendhigh", "trendmid", "trendlow");

        // Ranks are 1-based and increase monotonically down the response.
        List<Integer> ranks = response.items().stream()
                .map(TrendingHashtagsResponse.TrendingHashtagItem::rank)
                .toList();
        assertThat(ranks).startsWith(1).isSorted();

        // The fixture scores are returned verbatim.
        assertThat(response.items())
                .filteredOn(i -> i.tag().equals("trendhigh"))
                .first()
                .extracting(TrendingHashtagsResponse.TrendingHashtagItem::score)
                .isEqualTo(3L);
        assertThat(response.items())
                .filteredOn(i -> i.tag().equals("trendlow"))
                .first()
                .extracting(TrendingHashtagsResponse.TrendingHashtagItem::score)
                .isEqualTo(1L);

        assertThat(response.windowDescription()).isNotBlank();
        assertThat(response.totalItems()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void trending_equalScores_tieBreakByTagAscending() throws Exception
    {
        AuthResponse author = registerUser("tndb");

        // Same score (1 qualifying tweet each); "trendzeta" must rank before
        // "trendalpha" because the tag ASC tie-breaker is inverted-alphabetical
        // relative to their names.
        insertTaggedTweet(author.userId(), "trendzeta", OffsetDateTime.now().minusHours(1));
        insertTaggedTweet(author.userId(), "trendalpha", OffsetDateTime.now().minusHours(1));

        TrendingHashtagsResponse response = getTrending(50, author.token());

        List<String> ourTags = response.items().stream()
                .map(TrendingHashtagsResponse.TrendingHashtagItem::tag)
                .filter(tag -> tag.equals("trendzeta") || tag.equals("trendalpha"))
                .toList();

        assertThat(ourTags).containsExactly("trendalpha", "trendzeta");
    }

    @Test
    void trending_usageCountDoesNotInflateScore() throws Exception
    {
        AuthResponse author = registerUser("tndc");

        // One tweet repeating the tag three times -> ONE tweet_hashtags row
        // with usage_count=3; the distinct-tweet score must be 1, not 3.
        TweetResponse tweet = createTweet(author, "#reptag #reptag #reptag");

        assertThat(countUsage("reptag", tweet.id())).isEqualTo(3);

        TrendingHashtagsResponse response = getTrending(50, author.token());

        assertThat(response.items())
                .filteredOn(i -> i.tag().equals("reptag"))
                .first()
                .extracting(TrendingHashtagsResponse.TrendingHashtagItem::score)
                .isEqualTo(1L);
    }

    // ========================================================================
    // Time window & boundary semantics [windowStart, windowEnd)
    // ========================================================================

    @Test
    void trending_windowBoundarySemantics_areHalfOpen() throws Exception
    {
        AuthResponse author = registerUser("tndd");

        OffsetDateTime now = OffsetDateTime.now();

        // Deterministic for any execution speed (see class javadoc):
        // - 60s before the window start  -> excluded (outside, past)
        // - 60s after the window start   -> included (just inside)
        // - 60s in the future            -> excluded (at/after windowEnd)
        // - comfortably inside           -> included
        insertTaggedTweet(author.userId(), "windowtag", now.minusHours(24).minusSeconds(60));
        UUID insideNearStart =
                insertTaggedTweet(author.userId(), "windowtag", now.minusHours(24).plusSeconds(60));
        insertTaggedTweet(author.userId(), "windowtag", now.plusSeconds(60));
        UUID comfortablyInside =
                insertTaggedTweet(author.userId(), "windowtag", now.minusHours(1));

        assertThat(insideNearStart).isNotNull();
        assertThat(comfortablyInside).isNotNull();

        TrendingHashtagsResponse response = getTrending(50, author.token());

        assertThat(response.items())
                .filteredOn(i -> i.tag().equals("windowtag"))
                .first()
                .extracting(TrendingHashtagsResponse.TrendingHashtagItem::score)
                .as("exactly the two in-window tweets count: [start,end) half-open")
                .isEqualTo(2L);
    }

    @Test
    void trending_outsideWindowTagsAreAbsent() throws Exception
    {
        AuthResponse author = registerUser("tnde");

        // The only tweet carrying this tag is far outside the window.
        insertTaggedTweet(author.userId(), "oldtagonly", OffsetDateTime.now().minusDays(30));

        TrendingHashtagsResponse response = getTrending(50, author.token());

        assertThat(response.items())
                .extracting(TrendingHashtagsResponse.TrendingHashtagItem::tag)
                .doesNotContain("oldtagonly");
    }

    // ========================================================================
    // Lifecycle
    // ========================================================================

    @Test
    void trending_softDeletedTweetDoesNotCount() throws Exception
    {
        AuthResponse author = registerUser("tndf");

        TweetResponse kept = createTweet(author, "kept post #deletag");
        TweetResponse removed = createTweet(author, "removed post #deletag");
        deleteTweet(removed.id(), author.token());

        TrendingHashtagsResponse response = getTrending(50, author.token());

        assertThat(response.items())
                .filteredOn(i -> i.tag().equals("deletag"))
                .first()
                .extracting(TrendingHashtagsResponse.TrendingHashtagItem::score)
                .isEqualTo(1L);
    }

    @Test
    void trending_deletedAuthorDoesNotCount() throws Exception
    {
        AuthResponse author = registerUser("tndg");
        AuthResponse viewer = registerUser("tndh");

        createTweet(author, "author vanishes #ghostag");

        // Soft-delete the author (mirrors DeleteAccountUseCase semantics).
        jdbcTemplate.update("UPDATE users SET is_deleted = true WHERE id = ?", author.userId());

        TrendingHashtagsResponse response = getTrending(50, viewer.token());

        assertThat(response.items())
                .extracting(TrendingHashtagsResponse.TrendingHashtagItem::tag)
                .doesNotContain("ghostag");
    }

    @Test
    void trending_retweetMarkersDoNotCount() throws Exception
    {
        AuthResponse author = registerUser("tndi");
        AuthResponse retweeter = registerUser("tndj");

        TweetResponse original = createTweet(author, "original post #rttag");
        retweet(original.id(), retweeter.token());

        TrendingHashtagsResponse response = getTrending(50, author.token());

        // The retweet marker row must not add a second score point.
        assertThat(response.items())
                .filteredOn(i -> i.tag().equals("rttag"))
                .first()
                .extracting(TrendingHashtagsResponse.TrendingHashtagItem::score)
                .isEqualTo(1L);
    }

    // ========================================================================
    // Persistence, repeatability, limit, auth
    // ========================================================================

    @Test
    void trending_repeatedCallsProduceIdenticalOrdering() throws Exception
    {
        AuthResponse author = registerUser("tndk");

        insertTaggedTweet(author.userId(), "stableb", OffsetDateTime.now().minusHours(2));
        insertTaggedTweet(author.userId(), "stableb", OffsetDateTime.now().minusHours(3));
        insertTaggedTweet(author.userId(), "stablea", OffsetDateTime.now().minusHours(2));
        insertTaggedTweet(author.userId(), "stablec", OffsetDateTime.now().minusHours(2));

        TrendingHashtagsResponse run1 = getTrending(50, author.token());
        TrendingHashtagsResponse run2 = getTrending(50, author.token());

        List<String> ours1 = ourTags(run1);
        List<String> ours2 = ourTags(run2);
        assertThat(ours1).containsExactly("stableb", "stablea", "stablec");
        assertThat(ours2).isEqualTo(ours1);

        // Full response order is stable too (no incidental row shuffles).
        assertThat(run1.items()).usingRecursiveFieldByFieldElementComparator()
                .isEqualTo(run2.items());
    }

    @Test
    void trending_limitIsApplied_serverSide() throws Exception
    {
        AuthResponse author = registerUser("tndl");

        insertTaggedTweet(author.userId(), "limhigh", OffsetDateTime.now().minusHours(1));
        insertTaggedTweet(author.userId(), "limmid", OffsetDateTime.now().minusHours(2));
        insertTaggedTweet(author.userId(), "limlow", OffsetDateTime.now().minusHours(3));

        TrendingHashtagsResponse limited = getTrending(1, author.token());

        assertThat(limited.items()).hasSize(1);
        // The top row is the highest-scoring fixture.
        assertThat(limited.items().get(0).tag()).isEqualTo("limhigh");
        // totalItems still reflects the full qualifying set.
        assertThat(limited.totalItems()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void trending_unauthenticatedRequest_returns401() throws Exception
    {
        MockHttpServletResponse response = mockMvc.perform(post("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gson.toJson(new RequestEnvelope(
                                UUID.randomUUID(),
                                RequestType.TRENDING_HASHTAGS,
                                gson.toJsonTree(new GetTrendingHashtagsRequest(null, 10)),
                                null))))
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse();

        ResponseEnvelope envelope =
                gson.fromJson(response.getContentAsString(), ResponseEnvelope.class);
        assertThat(envelope.isSuccess()).isFalse();
        assertThat(envelope.errorCode()).isEqualTo("AUTH_REQUIRED");
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private AuthResponse registerUser(String prefix) throws Exception
    {
        String username = prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest registerRequest = new RegisterRequest(
                username,
                username + "@trendingtest.com",
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

    /**
     * Inserts a tweet plus its hashtag relationship directly via JDBC so the
     * test controls {@code published_at} precisely (the API always stamps the
     * server clock). Creates the canonical hashtag row when absent.
     *
     * @return the inserted tweet id
     */
    private UUID insertTaggedTweet(UUID authorId, String tag, OffsetDateTime publishedAt)
    {
        UUID tweetId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();

        jdbcTemplate.update(
                "INSERT INTO tweets (id, created_at, updated_at, is_deleted, content,"
                        + " published_at, author_id) VALUES (?, ?, ?, false, ?, ?, ?)",
                tweetId, now, now, "fixture tweet for " + tag, publishedAt, authorId);
        createdTweetIds.add(tweetId);

        Long existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM hashtags WHERE tag = ?", Long.class, tag);
        UUID hashtagId;
        if (existing != null && existing > 0)
        {
            hashtagId = jdbcTemplate.queryForObject(
                    "SELECT id FROM hashtags WHERE tag = ?", UUID.class, tag);
        }
        else
        {
            hashtagId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO hashtags (id, created_at, tag) VALUES (?, ?, ?)",
                    hashtagId, now, tag);
        }

        jdbcTemplate.update(
                "INSERT INTO tweet_hashtags (hashtag_id, tweet_id, usage_count)"
                        + " VALUES (?, ?, 1)",
                hashtagId, tweetId);

        return tweetId;
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

    private void retweet(UUID tweetId, String token) throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_RETWEET,
                gson.toJsonTree(new RetweetRequest(tweetId, token)),
                null));
        assertSuccess(envelope, "retweet " + tweetId);
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

    private int countUsage(String tag, UUID tweetId)
    {
        Integer usage = jdbcTemplate.queryForObject(
                "SELECT th.usage_count FROM tweet_hashtags th"
                        + " JOIN hashtags h ON h.id = th.hashtag_id"
                        + " WHERE h.tag = ? AND th.tweet_id = ?",
                Integer.class,
                tag,
                tweetId);
        return usage == null ? 0 : usage;
    }

    private List<String> ourTags(TrendingHashtagsResponse response)
    {
        return response.items().stream()
                .map(TrendingHashtagsResponse.TrendingHashtagItem::tag)
                .filter(tag -> tag.startsWith("stable"))
                .toList();
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
