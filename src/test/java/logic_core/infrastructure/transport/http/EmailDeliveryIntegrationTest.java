package logic_core.infrastructure.transport.http;

import com.google.gson.Gson;
import logic_core.app.dto.request.ConfirmEmailChangeRequest;
import logic_core.app.dto.request.ConfirmEmailVerificationRequest;
import logic_core.app.dto.request.RegisterRequest;
import logic_core.app.dto.request.RequestEmailVerificationRequest;
import logic_core.app.dto.request.RequestPasswordResetRequest;
import logic_core.app.dto.request.UpdateEmailRequest;
import logic_core.app.dto.response.AuthResponse;
import logic_core.app.dto.response.EmailChangeConfirmResponse;
import logic_core.app.dto.response.EmailVerificationConfirmResponse;
import logic_core.app.service.email.EmailMessage;
import logic_core.app.service.email.EmailMessageType;
import logic_core.app.service.email.EmailNotificationService;
import logic_core.infrastructure.email.LoggingEmailDeliveryAdapter;
import logic_core.infrastructure.transport.RequestEnvelope;
import logic_core.infrastructure.transport.RequestType;
import logic_core.infrastructure.transport.ResponseEnvelope;
import logic_core.infrastructure.transport.server.ServerMain;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
 * End-to-end email delivery coverage (Issue #20) over the real application
 * path ({@code POST /api} → dispatcher → use case → email service →
 * {@code LoggingEmailDeliveryAdapter}) against real PostgreSQL — no internet,
 * no real provider, no inbox.
 *
 * <p>Delivery is forced synchronous for determinism via the
 * {@code AsyncEmailDispatcher} seam; the adapter "delivery" is the dev logging
 * adapter, whose bounded recorded queue lets tests read the out-of-band code
 * that a real email would carry. The logged {@code EMAIL_*[DEV]} lines are
 * dev-only and never part of a production delivery path.
 */
@SpringBootTest(classes = ServerMain.class)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmailDeliveryIntegrationTest
{
    private static final int TEST_SOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerTestProperties(DynamicPropertyRegistry registry)
    {
        registry.add("server.socket.port", () -> TEST_SOCKET_PORT);
        // Default provider is 'log' (EMAIL_PROVIDER unset) — no credentials,
        // no internet.
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Gson gson;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LoggingEmailDeliveryAdapter deliveryAdapter;

    private final List<UUID> createdUserIds = new ArrayList<>();

    @BeforeAll
    static void forceSynchronousDelivery()
    {
        EmailNotificationService.AsyncEmailDispatcher.setDelegate(Runnable::run);
    }

    @AfterAll
    static void restoreAsyncDelivery()
    {
        EmailNotificationService.AsyncEmailDispatcher.setDelegate(null);
    }

    @BeforeEach
    void clearDeliveries()
    {
        deliveryAdapter.clear();
    }

    @AfterEach
    void cleanUpCreatedRows()
    {
        try
        {
            if (!createdUserIds.isEmpty())
            {
                String placeholders = repeatPlaceholders(createdUserIds.size());
                Object[] userArgs = createdUserIds.toArray();
                jdbcTemplate.update(
                        "DELETE FROM sessions WHERE user_id IN (" + placeholders + ")", userArgs);
                jdbcTemplate.update(
                        "DELETE FROM users WHERE id IN (" + placeholders + ")", userArgs);
            }
        }
        catch (Exception e)
        {
            System.err.println("EmailDeliveryIntegrationTest cleanup warning: " + e.getMessage());
        }
    }

    // ========================================================================
    // Password reset
    // ========================================================================

    @Test
    void passwordReset_requestIssuesCode_andVerifySucceeds() throws Exception
    {
        RegisteredUser user = registerUser("emai");

        ResponseEnvelope reset = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.AUTH_REQUEST_PASSWORD_RESET,
                gson.toJsonTree(new RequestPasswordResetRequest(user.email())),
                null));
        assertSuccess(reset, "password reset request");

        EmailMessage email = lastDelivery(EmailMessageType.PASSWORD_RESET);
        assertThat(email.to()).isEqualTo(user.email());
        String code = email.variables().get("code");
        assertThat(code).matches("\\d{6}");

        ResponseEnvelope verify = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.AUTH_VERIFY_PASSWORD_RESET_CODE,
                gson.toJsonTree(new logic_core.app.dto.request.VerifyPasswordResetCodeRequest(
                        user.email(), code)),
                null));
        assertSuccess(verify, "verify password reset code");
    }

    @Test
    void passwordReset_unknownEmail_isGenericSuccess_andDeliversNothing() throws Exception
    {
        long before = deliveryAdapter.recordedDeliveries().size();

        ResponseEnvelope reset = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.AUTH_REQUEST_PASSWORD_RESET,
                gson.toJsonTree(new RequestPasswordResetRequest("nobody_" + UUID.randomUUID() + "@nowhere.com")),
                null));
        assertSuccess(reset, "password reset request for unknown email");
        assertThat(deliveryAdapter.recordedDeliveries().size())
                .as("no email may be sent for unknown addresses")
                .isEqualTo(before);
    }

    @Test
    void passwordReset_rateLimit_blocksAfterThreeRequests() throws Exception
    {
        RegisteredUser user = registerUser("emab");

        for (int i = 0; i < 4; i++)
        {
            ResponseEnvelope reset = send(new RequestEnvelope(
                    UUID.randomUUID(),
                    RequestType.AUTH_REQUEST_PASSWORD_RESET,
                    gson.toJsonTree(new RequestPasswordResetRequest(user.email())),
                    null));
            assertSuccess(reset, "password reset request #" + (i + 1));
        }

        long delivered = deliveryAdapter.recordedDeliveries().stream()
                .filter(m -> m.type() == EmailMessageType.PASSWORD_RESET)
                .count();
        assertThat(delivered)
                .as("only the first 3 requests within the window deliver")
                .isEqualTo(3);
    }

    // ========================================================================
    // Email verification (self-service)
    // ========================================================================

    @Test
    void emailVerification_endToEnd_marksAccountVerified() throws Exception
    {
        RegisteredUser user = registerUser("emac");

        assertEmailVerified(user.auth().userId(), false);

        ResponseEnvelope request = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.EMAIL_VERIFY_REQUEST,
                gson.toJsonTree(new RequestEmailVerificationRequest(user.auth().token())),
                null));
        assertSuccess(request, "email verification request");

        String code = lastDelivery(EmailMessageType.EMAIL_VERIFICATION).variables().get("code");

        ResponseEnvelope confirm = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.EMAIL_VERIFY_CONFIRM,
                gson.toJsonTree(new ConfirmEmailVerificationRequest(user.auth().token(), code)),
                null));
        assertSuccess(confirm, "email verification confirm");

        EmailVerificationConfirmResponse response =
                gson.fromJson(confirm.getData(), EmailVerificationConfirmResponse.class);
        assertThat(response.verified()).isTrue();

        assertEmailVerified(user.auth().userId(), true);
    }

    @Test
    void emailVerification_unauthenticated_returns401() throws Exception
    {
        MockHttpServletResponse response = mockMvc.perform(post("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gson.toJson(new RequestEnvelope(
                                UUID.randomUUID(),
                                RequestType.EMAIL_VERIFY_REQUEST,
                                gson.toJsonTree(new RequestEmailVerificationRequest(null)),
                                null))))
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse();

        ResponseEnvelope envelope = gson.fromJson(response.getContentAsString(), ResponseEnvelope.class);
        assertThat(envelope.isSuccess()).isFalse();
        assertThat(envelope.errorCode()).isEqualTo("AUTH_REQUIRED");
    }

    // ========================================================================
    // Email change (old email authoritative until confirmed)
    // ========================================================================

    @Test
    void emailChange_newAddressIsPendingUntilConfirmed() throws Exception
    {
        RegisteredUser user = registerUser("emad");
        String newEmail = "new_" + UUID.randomUUID().toString().substring(0, 8) + "@emailitest.com";

        // Request the change: the authoritative email must NOT change yet.
        ResponseEnvelope update = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.USER_UPDATE_EMAIL,
                gson.toJsonTree(new UpdateEmailRequest(
                        user.auth().token(), user.auth().userId(), newEmail)),
                null));
        assertSuccess(update, "email change request");

        assertThat(dbEmail(user.auth().userId())).isEqualTo(user.email());
        assertThat(dbPendingEmail(user.auth().userId())).isEqualTo(newEmail);

        String code = lastDelivery(EmailMessageType.EMAIL_CHANGE).variables().get("code");

        // Wrong code must fail and leave the email unchanged.
        ResponseEnvelope wrong = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.EMAIL_CHANGE_CONFIRM,
                gson.toJsonTree(new ConfirmEmailChangeRequest(user.auth().token(), "000000")),
                null));
        assertThat(wrong.isSuccess()).as("wrong code must fail").isFalse();
        assertThat(dbEmail(user.auth().userId())).isEqualTo(user.email());

        // Correct code applies the change atomically.
        ResponseEnvelope confirm = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.EMAIL_CHANGE_CONFIRM,
                gson.toJsonTree(new ConfirmEmailChangeRequest(user.auth().token(), code)),
                null));
        assertSuccess(confirm, "email change confirm");

        EmailChangeConfirmResponse response =
                gson.fromJson(confirm.getData(), EmailChangeConfirmResponse.class);
        assertThat(response.changed()).isTrue();

        assertThat(dbEmail(user.auth().userId())).isEqualTo(newEmail);
        assertThat(dbPendingEmail(user.auth().userId())).isNull();
        assertEmailVerified(user.auth().userId(), true);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private record RegisteredUser(AuthResponse auth, String email)
    {
    }

    private RegisteredUser registerUser(String prefix) throws Exception
    {
        String username = prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
        String email = prefix + "_" + UUID.randomUUID().toString().substring(0, 8) + "@emailitest.com";
        RegisterRequest registerRequest = new RegisterRequest(
                username, email, "StrongPassword123!", "Display " + prefix);

        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.AUTH_REGISTER,
                gson.toJsonTree(registerRequest),
                null));
        assertSuccess(envelope, "register " + username);

        AuthResponse auth = gson.fromJson(envelope.getData(), AuthResponse.class);
        createdUserIds.add(auth.userId());
        return new RegisteredUser(auth, email);
    }

    private EmailMessage lastDelivery(EmailMessageType type)
    {
        List<EmailMessage> deliveries = deliveryAdapter.recordedDeliveries();
        for (int i = deliveries.size() - 1; i >= 0; i--)
        {
            if (deliveries.get(i).type() == type)
            {
                return deliveries.get(i);
            }
        }
        throw new AssertionError("no recorded delivery of type " + type);
    }

    private String dbEmail(UUID userId)
    {
        return jdbcTemplate.queryForObject(
                "SELECT email FROM users WHERE id = ?", String.class, userId);
    }

    private String dbPendingEmail(UUID userId)
    {
        return jdbcTemplate.queryForObject(
                "SELECT pending_email FROM users WHERE id = ?", String.class, userId);
    }

    private void assertEmailVerified(UUID userId, boolean expected)
    {
        Boolean verified = jdbcTemplate.queryForObject(
                "SELECT email_verified FROM users WHERE id = ?", Boolean.class, userId);
        assertThat(Boolean.TRUE.equals(verified))
                .as("email_verified for " + userId)
                .isEqualTo(expected);
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