package logic_core.infrastructure.transport.http;

import com.google.gson.Gson;
import logic_core.app.dto.request.BlockUserRequest;
import logic_core.app.dto.request.CreateTweetRequest;
import logic_core.app.dto.request.FollowUserRequest;
import logic_core.app.dto.request.GetIsFollowingRequest;
import logic_core.app.dto.request.GetIsLikedRequest;
import logic_core.app.dto.request.LikeTweetRequest;
import logic_core.app.dto.request.MuteUserRequest;
import logic_core.app.dto.request.RegisterRequest;
import logic_core.app.dto.request.UnblockUserRequest;
import logic_core.app.dto.request.UnfollowUserRequest;
import logic_core.app.dto.request.UnlikeTweetRequest;
import logic_core.app.dto.request.UnmuteUserRequest;
import logic_core.app.dto.response.AuthResponse;
import logic_core.app.dto.response.BlockActionResponse;
import logic_core.app.dto.response.FollowResponse;
import logic_core.app.dto.response.GetIsFollowingResponse;
import logic_core.app.dto.response.GetIsLikedResponse;
import logic_core.app.dto.response.MuteResponse;
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
 * Core integration suite — social relationship lifecycle.
 *
 * <p>Drives the full stack ({@code POST /api} → {@code RequestDispatcher} →
 * Facade → UseCase → Repository Adapter → JPA → PostgreSQL) for the
 * relationship operations that previously had no end-to-end coverage:
 * follow / unfollow, mute / unmute, block / unblock, and their observable
 * side effects (read state, counts, DB rows, interaction barriers).
 *
 * <p>Uses the same real-PostgreSQL harness as the other HTTP integration
 * tests: the Spring context boots against {@code xclonedb} (Flyway applies
 * {@code V1__baseline.sql}, Hibernate {@code ddl-auto=validate} passes) and
 * every row created here is removed in {@link #cleanUpCreatedRows()}.
 */
@SpringBootTest(classes = ServerMain.class)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RelationLifecycleIntegrationTest {

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
                        "DELETE FROM tweets WHERE id IN (" + placeholders + ")", tweetArgs);
            }
            if (!createdUserIds.isEmpty()) {
                String placeholders = repeatPlaceholders(createdUserIds.size());
                Object[] userArgs = createdUserIds.toArray();

                try {
                    jdbcTemplate.update(
                            "DELETE FROM blocks WHERE blocker_id IN (" + placeholders + ")"
                                    + " OR blocked_id IN (" + placeholders + ")",
                            doubleUserArgs());
                } catch (Exception ignored) {
                    // Best-effort only.
                }
                try {
                    jdbcTemplate.update(
                            "DELETE FROM mutes WHERE muter_id IN (" + placeholders + ")"
                                    + " OR muted_id IN (" + placeholders + ")",
                            doubleUserArgs());
                } catch (Exception ignored) {
                    // Best-effort only.
                }
                try {
                    jdbcTemplate.update(
                            "DELETE FROM follows WHERE follower_id IN (" + placeholders + ")"
                                    + " OR following_id IN (" + placeholders + ")",
                            doubleUserArgs());
                } catch (Exception ignored) {
                    // Best-effort only.
                }
                try {
                    jdbcTemplate.update(
                            "DELETE FROM notifications WHERE recipient_id IN (" + placeholders + ")"
                                    + " OR actor_id IN (" + placeholders + ")",
                            doubleUserArgs());
                } catch (Exception ignored) {
                    // Best-effort only; user deletion below remains authoritative.
                }

                jdbcTemplate.update(
                        "DELETE FROM sessions WHERE user_id IN (" + placeholders + ")", userArgs);
                jdbcTemplate.update(
                        "DELETE FROM users WHERE id IN (" + placeholders + ")", userArgs);
            }
        } catch (Exception e) {
            System.err.println("RelationLifecycleIntegrationTest cleanup warning: " + e.getMessage());
        }
    }

    // ========================================================================
    // Follow / unfollow
    // ========================================================================

    @Test
    void follow_thenUnfollow_roundTripThroughRealDatabase() throws Exception {
        AuthResponse follower = registerUser("rela");
        AuthResponse followed = registerUser("relb");

        FollowResponse follow = follow(follower, followed.userId());
        assertThat(follow.following()).isTrue();
        assertThat(follow.followersCount()).isEqualTo(1L);
        assertThat(countFollowRows(follower.userId(), followed.userId())).isEqualTo(1L);

        assertThat(isFollowing(follower.token(), followed.userId()).following()).isTrue();

        FollowResponse unfollow = unfollow(follower, followed.userId());
        assertThat(unfollow.following()).isFalse();
        assertThat(unfollow.followersCount()).isZero();

        assertThat(isFollowing(follower.token(), followed.userId()).following()).isFalse();
        assertThat(countFollowRows(follower.userId(), followed.userId())).isZero();
    }

    @Test
    void duplicateFollow_rejectedWithBusinessFailure() throws Exception {
        AuthResponse follower = registerUser("relc");
        AuthResponse followed = registerUser("reld");

        follow(follower, followed.userId());

        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.RELATION_FOLLOW,
                gson.toJsonTree(new FollowUserRequest(followed.userId(), follower.token())),
                null));
        assertThat(envelope.isSuccess()).isFalse();
        assertThat(envelope.errorCode()).isEqualTo("FOLLOW_FAILED");
        assertThat(envelope.errorMessage()).containsIgnoringCase("already exists");

        assertThat(countFollowRows(follower.userId(), followed.userId())).isEqualTo(1L);
    }

    // ========================================================================
    // Mute / unmute
    // ========================================================================

    @Test
    void mute_thenUnmute_roundTripThroughRealDatabase() throws Exception {
        AuthResponse muter = registerUser("rele");
        AuthResponse muted = registerUser("relf");

        MuteResponse mute = mute(muter, muted.userId());
        assertThat(mute.muted()).isTrue();
        assertThat(countMuteRows(muter.userId(), muted.userId())).isEqualTo(1L);

        MuteResponse unmute = unmute(muter, muted.userId());
        assertThat(unmute.muted()).isFalse();
        assertThat(countMuteRows(muter.userId(), muted.userId())).isZero();
    }

    // ========================================================================
    // Block / unblock
    // ========================================================================

    @Test
    void block_removesExistingFollowRelations_andUnblockRestoresInteractions() throws Exception {
        AuthResponse author = registerUser("relg");
        AuthResponse other = registerUser("relh");

        TweetResponse tweet = createTweet(author, "relation-block-1");

        // Other follows author first.
        follow(other, author.userId());
        assertThat(countFollowRows(other.userId(), author.userId())).isEqualTo(1L);

        // Author blocks other: BlockUserUseCase removes follow relations both ways.
        BlockActionResponse block = blockUser(author, other.userId());
        assertThat(block.blocked()).isTrue();
        assertThat(block.blockerId()).isEqualTo(author.userId());
        assertThat(block.blockedId()).isEqualTo(other.userId());
        assertThat(countFollowRows(other.userId(), author.userId()))
                .as("blocking must remove the existing follow relation")
                .isZero();
        assertThat(countBlockRows(author.userId(), other.userId())).isEqualTo(1L);

        // Blocked other cannot like the author's tweet.
        ResponseEnvelope blockedLike = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_LIKE,
                gson.toJsonTree(new LikeTweetRequest(tweet.id(), other.token())),
                null));
        assertThat(blockedLike.isSuccess()).isFalse();
        assertThat(blockedLike.errorCode()).isEqualTo("TWEET_LIKE_FAILED");

        // Unblock restores interaction.
        BlockActionResponse unblock = unblockUser(author, other.userId());
        assertThat(unblock.blocked()).isFalse();
        assertThat(countBlockRows(author.userId(), other.userId())).isZero();

        like(other, tweet.id());
        assertThat(isLiked(other.token(), tweet.id()).liked()).isTrue();

        // Clean the like so the follow-up assertion below stays unambiguous.
        unlike(other, tweet.id());
        assertThat(isLiked(other.token(), tweet.id()).liked()).isFalse();
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private AuthResponse registerUser(String prefix) throws Exception {
        String username = prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest registerRequest = new RegisterRequest(
                username,
                username + "@relationtest.com",
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

    private FollowResponse follow(AuthResponse follower, UUID followingId) throws Exception {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.RELATION_FOLLOW,
                gson.toJsonTree(new FollowUserRequest(followingId, follower.token())),
                null));
        assertSuccess(envelope, "follow " + followingId);
        return gson.fromJson(envelope.getData(), FollowResponse.class);
    }

    private FollowResponse unfollow(AuthResponse follower, UUID unfollowedId) throws Exception {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.RELATION_UNFOLLOW,
                gson.toJsonTree(new UnfollowUserRequest(unfollowedId, follower.token())),
                null));
        assertSuccess(envelope, "unfollow " + unfollowedId);
        return gson.fromJson(envelope.getData(), FollowResponse.class);
    }

    private MuteResponse mute(AuthResponse muter, UUID targetId) throws Exception {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.RELATION_MUTE,
                gson.toJsonTree(new MuteUserRequest(targetId, muter.token())),
                null));
        assertSuccess(envelope, "mute " + targetId);
        return gson.fromJson(envelope.getData(), MuteResponse.class);
    }

    private MuteResponse unmute(AuthResponse muter, UUID targetId) throws Exception {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.RELATION_UNMUTE,
                gson.toJsonTree(new UnmuteUserRequest(targetId, muter.token())),
                null));
        assertSuccess(envelope, "unmute " + targetId);
        return gson.fromJson(envelope.getData(), MuteResponse.class);
    }

    private BlockActionResponse blockUser(AuthResponse blocker, UUID blockedId) throws Exception {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.RELATION_BLOCK,
                gson.toJsonTree(new BlockUserRequest(blockedId, blocker.token())),
                null));
        assertSuccess(envelope, "block " + blockedId);
        return gson.fromJson(envelope.getData(), BlockActionResponse.class);
    }

    private BlockActionResponse unblockUser(AuthResponse blocker, UUID blockedId) throws Exception {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.RELATION_UNBLOCK,
                gson.toJsonTree(new UnblockUserRequest(blockedId, blocker.token())),
                null));
        assertSuccess(envelope, "unblock " + blockedId);
        return gson.fromJson(envelope.getData(), BlockActionResponse.class);
    }

    private GetIsFollowingResponse isFollowing(String token, UUID targetUserId) throws Exception {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.USER_GET_IS_FOLLOW,
                gson.toJsonTree(new GetIsFollowingRequest(token, targetUserId)),
                null));
        assertSuccess(envelope, "is-follow check for " + targetUserId);
        return gson.fromJson(envelope.getData(), GetIsFollowingResponse.class);
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

    private long countFollowRows(UUID followerId, UUID followingId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM follows WHERE follower_id = ? AND following_id = ?",
                Long.class,
                followerId,
                followingId);
        return count == null ? 0L : count;
    }

    private long countMuteRows(UUID muterId, UUID mutedId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mutes WHERE muter_id = ? AND muted_id = ?",
                Long.class,
                muterId,
                mutedId);
        return count == null ? 0L : count;
    }

    private long countBlockRows(UUID blockerId, UUID blockedId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM blocks WHERE blocker_id = ? AND blocked_id = ?",
                Long.class,
                blockerId,
                blockedId);
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

    private Object[] doubleUserArgs() {
        Object[] userArgs = createdUserIds.toArray();
        Object[] doubleArgs = new Object[createdUserIds.size() * 2];
        System.arraycopy(userArgs, 0, doubleArgs, 0, createdUserIds.size());
        System.arraycopy(userArgs, 0, doubleArgs, createdUserIds.size(), createdUserIds.size());
        return doubleArgs;
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
