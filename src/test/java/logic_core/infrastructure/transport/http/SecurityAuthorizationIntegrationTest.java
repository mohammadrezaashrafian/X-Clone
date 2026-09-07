package logic_core.infrastructure.transport.http;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import logic_core.app.dto.request.CreateTweetRequest;
import logic_core.app.dto.request.DeleteMediaRequest;
import logic_core.app.dto.request.DownloadMediaRequest;
import logic_core.app.dto.request.GetProfileRequest;
import logic_core.app.dto.request.GetTimelineRequest;
import logic_core.app.dto.request.LoginRequest;
import logic_core.app.dto.request.RegisterRequest;
import logic_core.app.dto.response.AuthResponse;
import logic_core.app.dto.response.DownloadMediaResponse;
import logic_core.app.dto.response.ProfileInfoResponse;
import logic_core.app.dto.response.TweetResponse;
import logic_core.domain.repository.TimelineType;
import logic_core.infrastructure.transport.RequestEnvelope;
import logic_core.infrastructure.transport.RequestType;
import logic_core.infrastructure.transport.ResponseEnvelope;
import logic_core.infrastructure.transport.server.ServerMain;
import logic_core.session.SessionManager;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Application-layer authorization integration tests (Phase 2 security fixes).
 *
 * <p>Exercises the two confirmed vulnerabilities through the full stack
 * ({@code POST /api} → {@code RequestDispatcher} → Facade/UseCase):
 *
 * <ul>
 *   <li>Timeline: {@code GetTimelineUseCase} must derive the actor from the
 *       authenticated session instead of trusting the caller-supplied
 *       {@code actorId} (HOME/FOLLOWING are actor-dependent feeds).</li>
 *   <li>Media download: {@code DownloadMediaUseCase} must require a valid
 *       session; media on public tweets remains readable by any authenticated
 *       user.</li>
 * </ul>
 */
@SpringBootTest(classes = ServerMain.class)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecurityAuthorizationIntegrationTest {

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

    @Autowired
    private SessionManager sessionManager;

    private final List<UUID> createdUserIds = new ArrayList<>();
    private final List<UUID> createdTweetIds = new ArrayList<>();

    @AfterEach
    void cleanUpCreatedRows() {
        try {
            if (!createdTweetIds.isEmpty()) {
                StringBuilder placeholders = new StringBuilder();
                for (int i = 0; i < createdTweetIds.size(); i++) {
                    if (i > 0) {
                        placeholders.append(",");
                    }
                    placeholders.append("?");
                }
                Object[] tweetArgs = createdTweetIds.toArray();
                jdbcTemplate.update(
                        "DELETE FROM media WHERE tweet_id IN (" + placeholders + ")",
                        tweetArgs
                );
                jdbcTemplate.update(
                        "DELETE FROM tweets WHERE id IN (" + placeholders + ")",
                        tweetArgs
                );
            }
            if (!createdUserIds.isEmpty()) {
                StringBuilder placeholders = new StringBuilder();
                for (int i = 0; i < createdUserIds.size(); i++) {
                    if (i > 0) {
                        placeholders.append(",");
                    }
                    placeholders.append("?");
                }
                jdbcTemplate.update(
                        "DELETE FROM sessions WHERE user_id IN (" + placeholders + ")",
                        createdUserIds.toArray()
                );
                jdbcTemplate.update(
                        "DELETE FROM users WHERE id IN (" + placeholders + ")",
                        createdUserIds.toArray()
                );
            }
        } catch (Exception e) {
            System.err.println("SecurityAuthorizationIntegrationTest cleanup warning: " + e.getMessage());
        }
    }

    // ========================================================================
    // Timeline: actor must be the authenticated session user
    // ========================================================================

    @Test
    void timeline_home_legitimateActor_returnsOwnTweets() throws Exception {
        AuthResponse userA = registerUser("seca");
        TweetResponse tweet = createTweet(userA, "timeline-own-1");

        ResponseEnvelope envelope = sendTimeline(TimelineType.HOME, userA.userId(), null, userA.token());
        assertSuccess(envelope, "legitimate home timeline");

        Set<UUID> tweetIds = extractTweetIds(envelope);
        assertThat(tweetIds).contains(tweet.id());
    }

    @Test
    void timeline_home_spoofedActorId_ignored_usesSessionUser() throws Exception {
        AuthResponse userA = registerUser("secb");
        TweetResponse tweetA = createTweet(userA, "timeline-secret-1");

        // User B holds a valid session but tries to read A's HOME feed by
        // supplying A's id as actorId. The actor must be derived from B's
        // session, so A's tweets must not appear.
        AuthResponse userB = registerUser("secc");
        ResponseEnvelope envelope = sendTimeline(TimelineType.HOME, userA.userId(), null, userB.token());
        assertSuccess(envelope, "spoofed home timeline still succeeds for the authenticated user");

        Set<UUID> tweetIds = extractTweetIds(envelope);
        assertThat(tweetIds)
                .as("attacker must not see the victim's HOME tweets")
                .doesNotContain(tweetA.id());
    }

    @Test
    void timeline_home_unauthenticated_rejected() throws Exception {
        AuthResponse userA = registerUser("secd");
        TweetResponse tweetA = createTweet(userA, "timeline-anon-1");

        // No sessionToken at all: the HTTP layer must reject the request with 401
        // Unauthorized even when a valid actorId is supplied (no spoofing).
        ResponseEnvelope envelope = sendUnauthorized(
                new RequestEnvelope(
                        UUID.randomUUID(),
                        RequestType.TIMELINE_GET,
                        gson.toJsonTree(new GetTimelineRequest(
                                TimelineType.HOME, userA.userId(), null, 0, 20, null)),
                        null
                )
        );
        assertThat(envelope.isSuccess())
                .as("unauthenticated timeline request must fail — error=%s",
                        envelope.errorMessage())
                .isFalse();
        assertThat(envelope.errorCode()).isEqualTo("AUTH_REQUIRED");
    }

    // ========================================================================
    // Media download: requires a session; public media stays readable
    // ========================================================================

    @Test
    void mediaDownload_authenticatedOwner_succeeds() throws Exception {
        AuthResponse userA = registerUser("sece");
        TweetResponse tweet = createTweet(userA, "media-owner-1", "https://example.com/media/owner.jpg");

        UUID mediaId = tweet.media().get(0).mediaId();
        ResponseEnvelope envelope = sendDownload(mediaId, userA.token());
        assertSuccess(envelope, "owner downloads own media");

        DownloadMediaResponse media = gson.fromJson(envelope.getData(), DownloadMediaResponse.class);
        assertThat(media.id()).isEqualTo(mediaId);
    }

    @Test
    void mediaDownload_unauthenticated_rejected() throws Exception {
        AuthResponse userA = registerUser("secf");
        TweetResponse tweet = createTweet(userA, "media-anon-1", "https://example.com/media/anon.jpg");

        UUID mediaId = tweet.media().get(0).mediaId();
        ResponseEnvelope envelope = sendUnauthorized(
                new RequestEnvelope(
                        UUID.randomUUID(),
                        RequestType.MEDIA_DOWNLOAD,
                        gson.toJsonTree(new DownloadMediaRequest(mediaId, null)),
                        null
                )
        );
        assertThat(envelope.isSuccess())
                .as("unauthenticated media download must fail — error=%s",
                        envelope.errorMessage())
                .isFalse();
        assertThat(envelope.errorCode()).isEqualTo("AUTH_REQUIRED");
    }

    @Test
    void mediaDownload_authenticatedOtherUser_allowed_publicMedia() throws Exception {
        AuthResponse userA = registerUser("secg");
        TweetResponse tweet = createTweet(userA, "media-public-1", "https://example.com/media/public.jpg");

        // Media attached to a public tweet stays readable by any authenticated
        // user — the fix only closes anonymous access, not cross-user public reads.
        AuthResponse userB = registerUser("sech");
        UUID mediaId = tweet.media().get(0).mediaId();
        ResponseEnvelope envelope = sendDownload(mediaId, userB.token());
        assertSuccess(envelope, "another authenticated user reads public tweet media");

        DownloadMediaResponse media = gson.fromJson(envelope.getData(), DownloadMediaResponse.class);
        assertThat(media.id()).isEqualTo(mediaId);
    }

    // ========================================================================
    // HTTP-layer authentication
    // ========================================================================

    @Test
    void httpAuth_validSession_reachesProtectedOperation() throws Exception {
        AuthResponse auth = registerUser("secj");

        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.USER_GET_PROFILE,
                gson.toJsonTree(new GetProfileRequest(auth.token(), auth.userId())),
                null
        ));
        assertSuccess(envelope, "authenticated profile request");

        ProfileInfoResponse profile = gson.fromJson(envelope.getData(), ProfileInfoResponse.class);
        assertThat(profile.userId()).isEqualTo(auth.userId());
    }

    @Test
    void httpAuth_missingCredentials_rejectedWith401() throws Exception {
        AuthResponse auth = registerUser("seck");

        // A caller-supplied userId is present but no session credentials are: the
        // HTTP layer must reject the request with 401 before any business logic.
        ResponseEnvelope envelope = sendUnauthorized(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.USER_GET_PROFILE,
                gson.toJsonTree(new GetProfileRequest(null, auth.userId())),
                null
        ));
        assertThat(envelope.isSuccess()).isFalse();
        assertThat(envelope.errorCode()).isEqualTo("AUTH_REQUIRED");
    }

    @Test
    void httpAuth_invalidToken_rejectedWith401() throws Exception {
        AuthResponse auth = registerUser("secl");

        ResponseEnvelope envelope = sendUnauthorized(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.USER_GET_PROFILE,
                gson.toJsonTree(new GetProfileRequest(
                        UUID.randomUUID().toString(), auth.userId())),
                null
        ));
        assertThat(envelope.isSuccess()).isFalse();
        assertThat(envelope.errorCode()).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void httpAuth_revokedSession_rejectedWith401() throws Exception {
        AuthResponse auth = registerUser("secm");

        // Revoke the session (logout semantics), then confirm the same token is no
        // longer accepted by the HTTP transport.
        sessionManager.invalidateSessionByToken(auth.token());

        ResponseEnvelope envelope = sendUnauthorized(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.USER_GET_PROFILE,
                gson.toJsonTree(new GetProfileRequest(auth.token(), auth.userId())),
                null
        ));
        assertThat(envelope.isSuccess()).isFalse();
        assertThat(envelope.errorCode()).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void httpAuth_anonymousRegisterAndLogin_doNotRequireCredentials() throws Exception {
        String username = "secn_" + UUID.randomUUID().toString().substring(0, 8);
        String password = "StrongPassword123!";

        RegisterRequest registerRequest =
                new RegisterRequest(username, username + "@authtest.com", password, "Display secn");
        ResponseEnvelope registerEnvelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.AUTH_REGISTER,
                gson.toJsonTree(registerRequest),
                null
        ));
        assertSuccess(registerEnvelope, "anonymous register");
        AuthResponse registered = gson.fromJson(registerEnvelope.getData(), AuthResponse.class);
        createdUserIds.add(registered.userId());

        LoginRequest loginRequest = new LoginRequest(username, password);
        ResponseEnvelope loginEnvelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.AUTH_LOGIN,
                gson.toJsonTree(loginRequest),
                null
        ));
        assertSuccess(loginEnvelope, "anonymous login");
        AuthResponse login = gson.fromJson(loginEnvelope.getData(), AuthResponse.class);
        assertThat(login.token()).isNotBlank();
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private AuthResponse registerUser(String prefix) throws Exception {
        String username = prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest registerRequest =
                new RegisterRequest(username, username + "@integrationtest.com", "StrongPassword123!", "Display " + prefix);

        RequestEnvelope request = new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.AUTH_REGISTER,
                gson.toJsonTree(registerRequest),
                null
        );

        ResponseEnvelope envelope = send(request);
        assertSuccess(envelope, "register " + username);

        AuthResponse auth = gson.fromJson(envelope.getData(), AuthResponse.class);
        createdUserIds.add(auth.userId());
        return auth;
    }

    private TweetResponse createTweet(AuthResponse auth, String content) throws Exception {
        return createTweet(auth, content, null);
    }

    private TweetResponse createTweet(AuthResponse auth, String content, String mediaUrl) throws Exception {
        List<String> mediaUrls = mediaUrl == null ? null : List.of(mediaUrl);
        CreateTweetRequest createRequest =
                new CreateTweetRequest(content, null, null, null, auth.token(), mediaUrls);

        RequestEnvelope request = new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_CREATE,
                gson.toJsonTree(createRequest),
                null
        );

        ResponseEnvelope envelope = send(request);
        assertSuccess(envelope, "create tweet " + content);

        TweetResponse tweet = gson.fromJson(envelope.getData(), TweetResponse.class);
        assertThat(tweet.id()).isNotNull();
        createdTweetIds.add(tweet.id());
        return tweet;
    }

    private ResponseEnvelope sendTimeline(TimelineType type, UUID actorId, UUID targetUserId, String token)
            throws Exception {
        GetTimelineRequest timelineRequest =
                new GetTimelineRequest(type, actorId, targetUserId, 0, 20, token);

        return send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TIMELINE_GET,
                gson.toJsonTree(timelineRequest),
                null
        ));
    }

    private ResponseEnvelope sendDownload(UUID mediaId, String token) throws Exception {
        DownloadMediaRequest downloadRequest = new DownloadMediaRequest(mediaId, token);
        return send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.MEDIA_DOWNLOAD,
                gson.toJsonTree(downloadRequest),
                null
        ));
    }

    private Set<UUID> extractTweetIds(ResponseEnvelope envelope) {
        Set<UUID> ids = new HashSet<>();
        JsonArray tweets = envelope.getData().getAsJsonObject().getAsJsonArray("tweets");
        for (JsonElement element : tweets) {
            ids.add(UUID.fromString(element.getAsJsonObject().get("tweetId").getAsString()));
        }
        return ids;
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

    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException("Could not find a free TCP port", e);
        }
    }
}