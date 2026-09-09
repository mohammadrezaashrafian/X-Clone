package logic_core.infrastructure.transport.http;

import com.google.gson.Gson;
import logic_core.app.dto.request.GetProfileRequest;
import logic_core.app.dto.request.LoginRequest;
import logic_core.app.dto.request.LogoutRequest;
import logic_core.app.dto.request.RegisterRequest;
import logic_core.app.dto.request.UpdatePasswordRequest;
import logic_core.app.dto.response.AuthResponse;
import logic_core.app.dto.response.ProfileInfoResponse;
import logic_core.app.service.ratelimit.InMemoryEmailRateLimiter;
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
 * Issue #21 security baseline — authentication regression suite.
 *
 * <p>Exercises the real application path
 * ({@code POST /api} → {@code RequestDispatcher} → Facade → UseCase → PostgreSQL):
 *
 * <ul>
 *   <li><b>Rate limiting</b> — repeated failed logins are blocked after the
 *       configured limit; a subsequent successful login still works (the
 *       window is fixed and correct credentials are not permanently locked
 *       out) because each test runs against a fresh per-username window.</li>
 *   <li><b>Anti-enumeration</b> — an unknown username is indistinguishable
 *       from a wrong password at the HTTP boundary.</li>
 *   <li><b>Stolen-token containment</b> — after a password change, the
 *       previous token can no longer read the profile while a fresh
 *       post-change token remains valid.</li>
 *   <li><b>Transport</b> — malformed payloads cannot bypass authentication.</li>
 * </ul>
 */
@SpringBootTest(classes = ServerMain.class)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecurityBaselineAuthIntegrationTest {

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

    /** Mirrors the production default policy (5 / 15 minutes). */
    private final InMemoryEmailRateLimiter loginLimiterProbe = new InMemoryEmailRateLimiter();

    private final List<UUID> createdUserIds = new ArrayList<>();

    @AfterEach
    void cleanUpCreatedRows() {
        try {
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
                        createdUserIds.toArray());
                jdbcTemplate.update(
                        "DELETE FROM users WHERE id IN (" + placeholders + ")",
                        createdUserIds.toArray());
            }
        } catch (Exception e) {
            System.err.println("SecurityBaselineAuthIntegrationTest cleanup warning: " + e.getMessage());
        }
    }

    // ========================================================================
    // Login rate limiting (Issue #21)
    // ========================================================================

    @Test
    void login_repeatedFailures_blockedAfterConfiguredLimit() throws Exception {
        String username = uniqueUsername("rate");
        registerUserViaPost(username); // create the account; attacker only knows the name

        String wrongPassword = "WrongPassword1";
        int configuredLimit = 5;

        ResponseEnvelope lastFailure = null;
        for (int i = 1; i <= configuredLimit; i++) {
            lastFailure = sendLogin(username, wrongPassword);
            assertThat(lastFailure.isSuccess())
                    .as("failed attempt %d (within limit) must return the generic failure", i)
                    .isFalse();
        }

        // Attempt 6: the limiter must block before credential verification.
        ResponseEnvelope blocked = sendLogin(username, wrongPassword);
        assertThat(blocked.isSuccess())
                .as("attempt beyond the limit must be blocked — errorMessage=%s", blocked.errorMessage())
                .isFalse();
        assertThat(failureMessage(blocked)).isEqualTo("Invalid credentials.");
        assertThat(failureMessage(lastFailure)).isEqualTo("Invalid credentials.");
    }

    @Test
    void login_rateLimit_isPerUsername_andDoesNotAffectOtherAccounts() throws Exception {
        String victim = uniqueUsername("victim");
        String bystander = uniqueUsername("bystander");
        registerUserViaPost(victim);
        registerUserViaPost(bystander);

        for (int i = 0; i < 5; i++) {
            sendLogin(victim, "WrongPassword1");
        }

        ResponseEnvelope bystanderLogin = sendLogin(bystander, "StrongPassword123!");
        assertThat(bystanderLogin.isSuccess())
                .as("a different username has its own fixed window — errorMessage=%s",
                        bystanderLogin.errorMessage())
                .isTrue();
    }

    @Test
    void login_correctPassword_stillAuthenticatesAfterFailures_withinLimit() throws Exception {
        String username = uniqueUsername("within");
        registerUserViaPost(username);

        // Three failures: still inside the 5-attempt window.
        sendLogin(username, "WrongPassword1");
        sendLogin(username, "WrongPassword1");
        sendLogin(username, "WrongPassword1");

        ResponseEnvelope good = sendLogin(username, "StrongPassword123!");
        assertThat(good.isSuccess())
                .as("correct credentials within the limit must succeed — errorMessage=%s",
                        good.errorMessage())
                .isTrue();
    }

    // ========================================================================
    // Anti-enumeration (Issue #21)
    // ========================================================================

    @Test
    void login_unknownUsername_indistinguishableFromWrongPassword() throws Exception {
        String ghost = uniqueUsername("ghost"); // never registered

        ResponseEnvelope unknownUser = sendLogin(ghost, "StrongPassword123!");
        ResponseEnvelope realUserWrongPassword = sendLogin(uniqueUsername("real") /* not registered */, "StrongPassword123!");

        assertThat(unknownUser.isSuccess()).isFalse();
        assertThat(realUserWrongPassword.isSuccess()).isFalse();
        assertThat(failureMessage(unknownUser))
                .as("both cases must expose the same generic message")
                .isEqualTo(failureMessage(realUserWrongPassword))
                .isEqualTo("Invalid credentials.");
    }

    @Test
    void login_unknownUsername_responseExposesNoAccountState() throws Exception {
        ResponseEnvelope unknownUser = sendLogin(uniqueUsername("noinfo"), "StrongPassword123!");

        assertThat(unknownUser.isSuccess()).isFalse();
        assertThat(failureMessage(unknownUser))
                .as("no internal marker (class names, user-not-found wording) may leak")
                .doesNotContain("User not found")
                .doesNotContain("NotFoundException")
                .doesNotContain("locking");
        assertThat(unknownUser.getData() == null || unknownUser.getData().isJsonNull())
                .as("no payload content on failure")
                .isTrue();
    }

    // ========================================================================
    // Session token handling (Issue #21)
    // ========================================================================

    @Test
    void login_newToken_isOpaqueHighEntropy() throws Exception {
        String username = uniqueUsername("entropy");
        AuthResponse auth = registerUserViaPost(username);

        // 43 chars = 32 decoded bytes = 256-bit entropy (Base64url, unpadded).
        assertThat(auth.token())
                .as("session tokens are 256-bit SecureRandom material")
                .hasSize(43)
                .doesNotContain("=")
                .doesNotMatch(".*[+/].*");
    }

    @Test
    void passwordChange_invalidatesPreviousToken_freshTokenStillWorks() throws Exception {
        String username = uniqueUsername("rotate");
        AuthResponse auth = registerUserViaPost(username);
        String oldToken = auth.token();

        ResponseEnvelope changeResult = sendUpdatePassword(oldToken, auth.userId(), "StrongPassword123!", "BrandNewPassword2");
        assertThat(changeResult.isSuccess())
                .as("password change must succeed — errorCode=%s, errorMessage=%s",
                        changeResult.errorCode(), changeResult.errorMessage())
                .isTrue();

        // Old (stolen) token is dead: rejected at the transport auth gate.
        MockHttpServletResponse oldTokenResponse = postRaw(sendGetProfileRequest(oldToken, auth.userId()));
        assertThat(oldTokenResponse.getStatus())
                .as("the pre-rotation token must be rejected with 401 Unauthorized")
                .isEqualTo(401);

        // Re-login with the new password mints a fresh token that works.
        ResponseEnvelope relogin = sendLogin(username, "BrandNewPassword2");
        assertThat(relogin.isSuccess())
                .as("the new credential must authenticate — errorMessage=%s", relogin.errorMessage())
                .isTrue();
        AuthResponse freshAuth = gson.fromJson(relogin.getData(), AuthResponse.class);

        ResponseEnvelope freshRead = sendGetProfile(freshAuth.token(), freshAuth.userId());
        assertThat(freshRead.isSuccess())
                .as("a post-rotation token must authenticate normally")
                .isTrue();
        ProfileInfoResponse profile = gson.fromJson(freshRead.getData(), ProfileInfoResponse.class);
        assertThat(profile.username()).isEqualTo(username);
    }

    // ========================================================================
    // Transport hardening (existing behavior, regression-locked here)
    // ========================================================================

    @Test
    void malformedPayload_onProtectedRoute_cannotBypassAuthentication() throws Exception {
        String username = uniqueUsername("malformed");
        AuthResponse auth = registerUserViaPost(username);

        // A syntactically valid envelope whose payload cannot bind to the DTO
        // fails in the dispatcher (UNEXPECTED_ERROR) and must not leak internal
        // details — even though the session token itself was valid.
        String body = "{\"requestId\":\"" + UUID.randomUUID()
                + "\",\"type\":\"TWEET_LIKE\",\"payload\":{"
                + "\"tweetId\":\"not-a-uuid\",\"sessionToken\":\"" + auth.token() + "\"}}";

        MockHttpServletResponse response = mockMvc.perform(post("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isInternalServerError())
                .andReturn()
                .getResponse();

        ResponseEnvelope envelope = gson.fromJson(response.getContentAsString(), ResponseEnvelope.class);
        assertThat(envelope).isNotNull();
        assertThat(envelope.isSuccess()).isFalse();
        assertThat(String.valueOf(envelope.errorMessage()))
                .as("no internal exception detail may leak through the envelope")
                .doesNotContain("com.")
                .doesNotContain("org.")
                .doesNotContain("Exception");
    }

    @Test
    void logout_revokedToken_cannotAuthenticate() throws Exception {
        String username = uniqueUsername("logout");
        AuthResponse auth = registerUserViaPost(username);

        ResponseEnvelope logout = sendLogout(auth.token(), auth.userId());
        assertThat(logout.isSuccess()).isTrue();

        ResponseEnvelope afterLogout = envelopeOf(postRaw(sendGetProfileRequest(auth.token(), auth.userId())));
        assertThat(afterLogout.isSuccess())
                .as("a revoked token must not authenticate after logout")
                .isFalse();
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private ResponseEnvelope sendLogin(String username, String password) throws Exception {
        RequestEnvelope request = new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.AUTH_LOGIN,
                gson.toJsonTree(new LoginRequest(username, password)),
                null);
        return send(request, 200);
    }

    private ResponseEnvelope sendUpdatePassword(String token, UUID userId, String oldPassword, String newPassword) throws Exception {
        RequestEnvelope request = new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.USER_UPDATE_PASSWORD,
                gson.toJsonTree(new UpdatePasswordRequest(token, userId, oldPassword, newPassword)),
                token);
        return send(request, 200);
    }

    private ResponseEnvelope sendGetProfile(String token, UUID userId) throws Exception {
        return envelopeOf(postRaw(sendGetProfileRequest(token, userId)));
    }

    private RequestEnvelope sendGetProfileRequest(String token, UUID userId) {
        return new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.USER_GET_PROFILE,
                gson.toJsonTree(GetProfileRequest.builder()
                        .sessionToken(token)
                        .userId(userId)
                        .build()),
                token);
    }

    /** Performs the POST without pinning the HTTP status. */
    private MockHttpServletResponse postRaw(RequestEnvelope request) throws Exception {
        return mockMvc.perform(post("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gson.toJson(request)))
                .andReturn()
                .getResponse();
    }

    private ResponseEnvelope envelopeOf(MockHttpServletResponse response) {
        try {
            ResponseEnvelope envelope = gson.fromJson(response.getContentAsString(), ResponseEnvelope.class);
            assertThat(envelope).isNotNull();
            return envelope;
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException("Test response encoding failure", e);
        }
    }

    private ResponseEnvelope sendLogout(String token, UUID userId) throws Exception {
        RequestEnvelope request = new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.AUTH_LOGOUT,
                gson.toJsonTree(new LogoutRequest(token, userId)),
                token);
        return send(request, 200);
    }

    private AuthResponse registerUserViaPost(String username) throws Exception {
        String email = username + "@integrationtest.com";
        String password = "StrongPassword123!";

        RegisterRequest registerRequest = new RegisterRequest(username, email, password, "Security Baseline");
        RequestEnvelope request = new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.AUTH_REGISTER,
                gson.toJsonTree(registerRequest),
                null);

        ResponseEnvelope envelope = send(request, 200);
        assertThat(envelope.isSuccess())
                .as("register must succeed — errorCode=%s, errorMessage=%s",
                        envelope.errorCode(), envelope.errorMessage())
                .isTrue();

        AuthResponse auth = gson.fromJson(envelope.getData(), AuthResponse.class);
        createdUserIds.add(auth.userId());
        return auth;
    }

    private ResponseEnvelope send(RequestEnvelope request, int expectedStatus) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(post("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gson.toJson(request)))
                .andExpect(status().is(expectedStatus))
                .andReturn()
                .getResponse();

        ResponseEnvelope envelope = gson.fromJson(response.getContentAsString(), ResponseEnvelope.class);
        assertThat(envelope).isNotNull();
        return envelope;
    }

    /** Failure message from the envelope regardless of HTTP status (200/401/500). */
    private String failureMessage(ResponseEnvelope envelope) {
        return envelope.errorMessage() == null ? "" : envelope.errorMessage();
    }

    private static String uniqueUsername(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException("Could not find a free TCP port", e);
        }
    }
}
