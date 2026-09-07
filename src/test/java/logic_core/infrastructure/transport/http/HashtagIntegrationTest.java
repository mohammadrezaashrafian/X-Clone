package logic_core.infrastructure.transport.http;

import com.google.gson.Gson;
import logic_core.app.dto.request.BlockUserRequest;
import logic_core.app.dto.request.CreateTweetRequest;
import logic_core.app.dto.request.DeleteTweetRequest;
import logic_core.app.dto.request.FollowHashtagRequest;
import logic_core.app.dto.request.GetHashtagTweetsRequest;
import logic_core.app.dto.request.RegisterRequest;
import logic_core.app.dto.request.ReplyTweetRequest;
import logic_core.app.dto.request.UnfollowHashtagRequest;
import logic_core.app.dto.response.AuthResponse;
import logic_core.app.dto.response.BlockActionResponse;
import logic_core.app.dto.response.HashtagFollowResponse;
import logic_core.app.dto.response.HashtagTweetsResponse;
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
 * Core integration suite — hashtag system.
 *
 * <p>Drives the full stack ({@code POST /api} → {@code RequestDispatcher} →
 * Facade → UseCase → Repository Adapter → JPA → PostgreSQL) for the V2.1
 * hashtag feature: extraction from tweet content, persistence into the
 * {@code hashtags} / {@code tweet_hashtags} tables, hashtag feed retrieval
 * with the existing block/mute/soft-delete visibility semantics, and
 * authenticated hashtag follow/unfollow.
 *
 * <p>Uses the same real-PostgreSQL harness as the other HTTP integration
 * tests: the Spring context boots against {@code xclonedb} (Flyway applies
 * {@code V1__baseline.sql}, Hibernate {@code ddl-auto=validate} passes) and
 * every row created here is removed in {@link #cleanUpCreatedRows()}.
 */
@SpringBootTest(classes = ServerMain.class)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HashtagIntegrationTest
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

                // Remove hashtag rows referenced by this test's tweets/follows
                // first (cascades the tweet_hashtags / hashtag_follows rows).
                jdbcTemplate.update(
                        "DELETE FROM hashtags WHERE id IN ("
                                + " SELECT hashtag_id FROM tweet_hashtags WHERE tweet_id IN ("
                                + placeholders + ")"
                                + " UNION "
                                + " SELECT hashtag_id FROM hashtag_follows WHERE user_id IN ("
                                + repeatPlaceholders(createdUserIds.size()) + ")"
                                + ")",
                        concat(tweetArgs, createdUserIds.toArray()));

                jdbcTemplate.update(
                        "DELETE FROM likes WHERE tweet_id IN (" + placeholders + ")",
                        tweetArgs);
                jdbcTemplate.update(
                        "DELETE FROM tweet_edits WHERE tweet_id IN (" + placeholders + ")",
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
                            "DELETE FROM hashtag_follows WHERE user_id IN ("
                                    + placeholders + ")",
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
            System.err.println("HashtagIntegrationTest cleanup warning: " + e.getMessage());
        }
    }

    // ========================================================================
    // Extraction + persistence
    // ========================================================================

    @Test
    void tweetCreate_extractsNormalizesAndPersistsHashtags() throws Exception
    {
        AuthResponse author = registerUser("hase");

        TweetResponse tweet = createTweet(author, "Loving #Java and #java and #Spring today");

        // Distinct canonical tags, lowercased, one hashtags row each.
        assertThat(countHashtagRows("java")).isEqualTo(1L);
        assertThat(countHashtagRows("spring")).isEqualTo(1L);
        assertThat(countHashtagRows("Java")).isZero();

        // One tweet_hashtags row per distinct tag; usage_count counts occurrences.
        assertThat(tweetHashtagUsage("java", tweet.id())).isEqualTo(2);
        assertThat(tweetHashtagUsage("spring", tweet.id())).isEqualTo(1);
    }

    @Test
    void reply_extractsAndPersistsHashtags() throws Exception
    {
        AuthResponse author = registerUser("hasr");

        TweetResponse parent = createTweet(author, "parent without tags");
        TweetResponse reply = reply(author, parent.id(), "my answer #replytag");

        assertThat(countHashtagRows("replytag")).isEqualTo(1L);
        assertThat(tweetHashtagUsage("replytag", reply.id())).isEqualTo(1);

        // Parent tweet has no hashtags attached.
        assertThat(countTweetHashtagRowsForTweet(parent.id())).isZero();
    }

    // ========================================================================
    // Hashtag feed
    // ========================================================================

    @Test
    void hashtagFeed_returnsVisibleTweets_paginated() throws Exception
    {
        AuthResponse a = registerUser("hasf");
        AuthResponse b = registerUser("hasg");

        TweetResponse t1 = createTweet(a, "one #summer post");
        TweetResponse t2 = createTweet(b, "two #summer post");
        TweetResponse t3 = createTweet(a, "three #summer post");

        HashtagTweetsResponse page0 = getHashtagTweets("summer", 0, 2, a.token());
        assertThat(page0.totalItems()).isEqualTo(3);
        assertThat(page0.tweets()).hasSize(2);
        assertThat(page0.hasNext()).isTrue();

        HashtagTweetsResponse page1 = getHashtagTweets("summer", 1, 2, a.token());
        assertThat(page1.tweets()).hasSize(1);
        assertThat(page1.hasNext()).isFalse();

        // All three tweets are reachable across the two pages.
        List<UUID> seen = new ArrayList<>();
        page0.tweets().forEach(t -> seen.add(t.tweetId()));
        page1.tweets().forEach(t -> seen.add(t.tweetId()));
        assertThat(seen).containsExactlyInAnyOrder(t1.id(), t2.id(), t3.id());
    }

    @Test
    void hashtagFeed_excludesBlockedAndDeletedTweets() throws Exception
    {
        AuthResponse viewer = registerUser("hash");
        AuthResponse author = registerUser("hasi");

        TweetResponse visible = createTweet(author, "#private feed post");
        TweetResponse deleted = createTweet(author, "#private deleted post");

        deleteTweet(deleted.id(), author.token());

        // Before blocking: the deleted tweet is already excluded.
        HashtagTweetsResponse before = getHashtagTweets("private", 0, 20, viewer.token());
        assertThat(before.totalItems()).isEqualTo(1);
        assertThat(before.tweets().get(0).tweetId()).isEqualTo(visible.id());

        // Viewer blocks author: the remaining tweet becomes invisible.
        block(viewer, author.userId());

        HashtagTweetsResponse after = getHashtagTweets("private", 0, 20, viewer.token());
        assertThat(after.totalItems()).isZero();
        assertThat(after.tweets()).isEmpty();
    }

    @Test
    void hashtagFeed_unknownTag_returnsEmptyFeed() throws Exception
    {
        AuthResponse viewer = registerUser("hasj");

        HashtagTweetsResponse response =
                getHashtagTweets("doesnotexist", 0, 20, viewer.token());

        assertThat(response.tweets()).isEmpty();
        assertThat(response.totalItems()).isZero();
        assertThat(response.hasNext()).isFalse();
    }

    // ========================================================================
    // Follow / unfollow
    // ========================================================================

    @Test
    void follow_thenUnfollow_roundTrip_withDuplicatePrevention() throws Exception
    {
        AuthResponse user = registerUser("hask");

        HashtagFollowResponse follow = followHashtag("#JAVA", user.token());
        assertThat(follow.following()).isTrue();
        assertThat(follow.followersCount()).isEqualTo(1L);

        // Case-insensitive normalization: the same hashtag is not followed twice.
        ResponseEnvelope duplicate = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.HASHTAG_FOLLOW,
                gson.toJsonTree(new FollowHashtagRequest("java", user.token())),
                null));
        assertThat(duplicate.isSuccess()).isFalse();
        assertThat(duplicate.errorCode()).isEqualTo("HASHTAG_FOLLOW_FAILED");
        assertThat(duplicate.errorMessage()).containsIgnoringCase("already following");

        HashtagFollowResponse unfollow = unfollowHashtag("java", user.token());
        assertThat(unfollow.following()).isFalse();
        assertThat(unfollow.followersCount()).isZero();

        // Unfollowing again fails.
        ResponseEnvelope again = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.HASHTAG_UNFOLLOW,
                gson.toJsonTree(new UnfollowHashtagRequest("java", user.token())),
                null));
        assertThat(again.isSuccess()).isFalse();
        assertThat(again.errorCode()).isEqualTo("HASHTAG_UNFOLLOW_FAILED");
    }

    @Test
    void unfollow_removesOnlyOwnFollow() throws Exception
    {
        AuthResponse userA = registerUser("hasl");
        AuthResponse userB = registerUser("hasm");

        followHashtag("music", userA.token());
        followHashtag("music", userB.token());

        HashtagFollowResponse afterA = unfollowHashtag("music", userA.token());
        assertThat(afterA.following()).isFalse();
        assertThat(afterA.followersCount())
                .as("user B's follow must remain untouched")
                .isEqualTo(1L);
    }

    @Test
    void follow_requiresAuthentication() throws Exception
    {
        MockHttpServletResponse response = mockMvc.perform(post("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gson.toJson(new RequestEnvelope(
                                UUID.randomUUID(),
                                RequestType.HASHTAG_FOLLOW,
                                gson.toJsonTree(new FollowHashtagRequest("java", null)),
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
                username + "@hashtagtest.com",
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

    private TweetResponse reply(AuthResponse author, UUID parentTweetId, String text)
            throws Exception
    {
        ReplyTweetRequest replyRequest =
                new ReplyTweetRequest(parentTweetId, text, null, author.token());
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_REPLY,
                gson.toJsonTree(replyRequest),
                null));
        assertSuccess(envelope, "reply " + text);

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

    private BlockActionResponse block(AuthResponse blocker, UUID blockedId) throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.RELATION_BLOCK,
                gson.toJsonTree(new BlockUserRequest(blockedId, blocker.token())),
                null));
        assertSuccess(envelope, "block " + blockedId);
        return gson.fromJson(envelope.getData(), BlockActionResponse.class);
    }

    private HashtagFollowResponse followHashtag(String tag, String token) throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.HASHTAG_FOLLOW,
                gson.toJsonTree(new FollowHashtagRequest(tag, token)),
                null));
        assertSuccess(envelope, "follow hashtag " + tag);
        return gson.fromJson(envelope.getData(), HashtagFollowResponse.class);
    }

    private HashtagFollowResponse unfollowHashtag(String tag, String token) throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.HASHTAG_UNFOLLOW,
                gson.toJsonTree(new UnfollowHashtagRequest(tag, token)),
                null));
        assertSuccess(envelope, "unfollow hashtag " + tag);
        return gson.fromJson(envelope.getData(), HashtagFollowResponse.class);
    }

    private HashtagTweetsResponse getHashtagTweets(
            String tag,
            int page,
            int pageSize,
            String token) throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.HASHTAG_GET_TWEETS,
                gson.toJsonTree(new GetHashtagTweetsRequest(tag, page, pageSize, token)),
                null));
        assertSuccess(envelope, "hashtag feed " + tag);
        return gson.fromJson(envelope.getData(), HashtagTweetsResponse.class);
    }

    private long countHashtagRows(String tag)
    {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM hashtags WHERE tag = ?",
                Long.class,
                tag);
        return count == null ? 0L : count;
    }

    private int tweetHashtagUsage(String tag, UUID tweetId)
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

    private long countTweetHashtagRowsForTweet(UUID tweetId)
    {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tweet_hashtags WHERE tweet_id = ?",
                Long.class,
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

    private static Object[] concat(Object[] first, Object[] second)
    {
        Object[] result = new Object[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
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