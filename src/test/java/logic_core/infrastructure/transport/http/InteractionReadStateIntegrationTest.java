package logic_core.infrastructure.transport.http;

import com.google.gson.Gson;
import logic_core.app.dto.request.CreateTweetRequest;
import logic_core.app.dto.request.GetIsLikedRequest;
import logic_core.app.dto.request.GetProfileRequest;
import logic_core.app.dto.request.GetTweetRequest;
import logic_core.app.dto.request.LikeTweetRequest;
import logic_core.app.dto.request.RegisterRequest;
import logic_core.app.dto.request.UnlikeTweetRequest;
import logic_core.app.dto.response.AuthResponse;
import logic_core.app.dto.response.GetIsLikedResponse;
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
 * Core integration suite — interaction read-state persistence and session
 * expiry at the HTTP boundary.
 *
 * <p>Proves that interaction state written through
 * {@code UseCase → Repository Adapter → JPA → PostgreSQL} is visible on a
 * subsequent read request through the application layer (cross-request
 * persistence), and that an expired session is rejected by the HTTP
 * authentication gate with the same 401 semantics as invalid/revoked tokens.
 */
@SpringBootTest(classes = ServerMain.class)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InteractionReadStateIntegrationTest {

    private static final int TEST_SOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerTestProperties(DynamicPropertyRegistry registry) {
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
    void cleanUpCreatedRows() {
        try {
            if (!createdTweetIds.isEmpty()) {
                String placeholders = repeatPlaceholders(createdTweetIds.size());
                Object[] tweetArgs = createdTweetIds.toArray();
                jdbcTemplate.update(
                        "DELETE FROM likes WHERE tweet_id IN (" + placeholders + ")", tweetArgs);
                jdbcTemplate.update(
                        "DELETE FROM tweet_edits WHERE tweet_id IN (" + placeholders + ")", tweetArgs);
                jdbcTemplate.update(
                        "DELETE FROM notifications WHERE tweet_id IN (" + placeholders + ")", tweetArgs);
                jdbcTemplate.update(
                        "DELETE FROM tweets WHERE id IN (" + placeholders + ")", tweetArgs);
            }
            if (!createdUserIds.isEmpty()) {
                String placeholders = repeatPlaceholders(createdUserIds.size());
                Object[] userArgs = createdUserIds.toArray();
                try {
                    Object[] doubleArgs = new Object[createdUserIds.size() * 2];
                    System.arraycopy(userArgs, 0, doubleArgs, 0, createdUserIds.size());
                    System.arraycopy(userArgs, 0, doubleArgs, createdUserIds.size(), createdUserIds.size());
                    jdbcTemplate.update(
                            "DELETE FROM notifications WHERE recipient_id IN (" + placeholders + ")"
                                    + " OR actor_id IN (" + placeholders + ")",
                            doubleArgs);
                } catch (Exception ignored) {
                    // Best-effort only; user deletion below remains authoritative.
                }
                jdbcTemplate.update(
                        "DELETE FROM sessions WHERE user_id IN (" + placeholders + ")", userArgs);
                jdbcTemplate.update(
                        "DELETE FROM users WHERE id IN (" + placeholders + ")", userArgs);
            }
        } catch (Exception e) {
            System.err.println("InteractionReadStateIntegrationTest cleanup warning: " + e.getMessage());
        }
    }

    // ========================================================================
    // Read-state persistence (like/unlike round trip through real PostgreSQL)
    // ========================================================================

    @Test
    void like_thenUnlike_readStateAndCountsSurviveRealDatabaseRoundTrips() throws Exception {
        AuthResponse author = registerUser("staa");
        AuthResponse liker = registerUser("stab");

        TweetResponse tweet = createTweet(author, "read-state-1");
        assertThat(isLiked(liker.token(), tweet.id()).liked()).isFalse();

        // Like through the application stack, then read the state back on a
        // separate request — the persisted row must be visible.
        like(liker, tweet.id());
        assertThat(isLiked(liker.token(), tweet.id()).liked()).isTrue();
        assertThat(countLikeRows(tweet.id())).isEqualTo(1L);

        TimelineTweet likedView = readSingleTweet(tweet.id(), liker.token());
        assertThat(likedView.likeCount()).isEqualTo(1L);

        // Unlike; the state and count must round-trip back to zero.
        unlike(liker, tweet.id());
        assertThat(isLiked(liker.token(), tweet.id()).liked()).isFalse();
        assertThat(countLikeRows(tweet.id())).isZero();

        TimelineTweet unlikedView = readSingleTweet(tweet.id(), liker.token());
        assertThat(unlikedView.likeCount()).isZero();
    }

    @Test
    void unlikeWithoutExistingLike_fails() throws Exception {
        AuthResponse author = registerUser("stac");
        AuthResponse liker = registerUser("stad");

        TweetResponse tweet = createTweet(author, "read-state-2");

        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_UNLIKE,
                gson.toJsonTree(new UnlikeTweetRequest(tweet.id(), liker.token())),
                null));
        assertThat(envelope.isSuccess()).isFalse();
        assertThat(envelope.errorCode()).isEqualTo("TWEET_UNLIKE_FAILED");
    }

    // ========================================================================
    // Session expiry at the HTTP boundary
    // ========================================================================

    @Test
    void expiredSession_rejectedWith401Unauthorized() throws Exception {
        AuthResponse auth = registerUser("stae");

        // Expire the session row directly in PostgreSQL — the token still
        // exists but is no longer valid.
        jdbcTemplate.update(
                "UPDATE sessions SET expires_at = now() - interval '1 hour' WHERE token = ?",
                auth.token());

        ResponseEnvelope envelope = sendUnauthorized(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.USER_GET_PROFILE,
                gson.toJsonTree(new GetProfileRequest(auth.token(), auth.userId())),
                null));
        assertThat(envelope.isSuccess()).isFalse();
        assertThat(envelope.errorCode()).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void sessionRowSurvivesPostgresWriteAndRemainsAccepted() throws Exception {
        AuthResponse auth = registerUser("staf");

        // Touch the session through a real database write, then confirm the
        // token still authenticates (persistence boundary round trip).
        jdbcTemplate.update(
                "UPDATE sessions SET created_at = now() WHERE token = ?",
                auth.token());

        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.USER_GET_PROFILE,
                gson.toJsonTree(new GetProfileRequest(auth.token(), auth.userId())),
                null));
        assertThat(envelope.isSuccess())
                .as("valid session must still authenticate — errorCode=%s, errorMessage=%s",
                        envelope.errorCode(), envelope.errorMessage())
                .isTrue();
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private AuthResponse registerUser(String prefix) throws Exception {
        String username = prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest registerRequest = new RegisterRequest(
                username,
                username + "@readstatetest.com",
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

    private TweetResponse createTweet(AuthResponse author, String content) throws Exception {
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

    private void like(AuthResponse liker, UUID tweetId) throws Exception {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_LIKE,
                gson.toJsonTree(new LikeTweetRequest(tweetId, liker.token())),
                null));
        assertSuccess(envelope, "like tweet " + tweetId);
    }

    private void unlike(AuthResponse liker, UUID tweetId) throws Exception {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_UNLIKE,
                gson.toJsonTree(new UnlikeTweetRequest(tweetId, liker.token())),
                null));
        assertSuccess(envelope, "unlike tweet " + tweetId);
    }

    private GetIsLikedResponse isLiked(String token, UUID tweetId) throws Exception {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.USER_GET_IS_LIKE,
                gson.toJsonTree(GetIsLikedRequest.builder()
                        .sessionToken(token)
                        .tweetId(tweetId)
                        .build()),
                null));
        assertSuccess(envelope, "is-like check for " + tweetId);
        return gson.fromJson(envelope.getData(), GetIsLikedResponse.class);
    }

    private TimelineTweet readSingleTweet(UUID tweetId, String token) throws Exception {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_GET,
                gson.toJsonTree(new GetTweetRequest(tweetId, token)),
                null));
        assertSuccess(envelope, "GET tweet " + tweetId);
        return gson.fromJson(envelope.getData(), TimelineTweet.class);
    }

    private long countLikeRows(UUID tweetId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM likes WHERE tweet_id = ?",
                Long.class,
                tweetId);
        return count == null ? 0L : count;
    }

    private ResponseEnvelope send(RequestEnvelope request) throws Exception {
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

    private ResponseEnvelope sendUnauthorized(RequestEnvelope request) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(post("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gson.toJson(request)))
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse();

        ResponseEnvelope envelope = gson.fromJson(response.getContentAsString(), ResponseEnvelope.class);
        assertThat(envelope).isNotNull();
        return envelope;
    }

    private void assertSuccess(ResponseEnvelope envelope, String step) {
        assertThat(envelope.isSuccess())
                .as("%s — errorCode=%s, errorMessage=%s", step, envelope.errorCode(), envelope.errorMessage())
                .isTrue();
    }

    private static String repeatPlaceholders(int count) {
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                placeholders.append(",");
            }
            placeholders.append("?");
        }
        return placeholders.toString();
    }

    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException("Could not find a free TCP port", e);
        }
    }
}
