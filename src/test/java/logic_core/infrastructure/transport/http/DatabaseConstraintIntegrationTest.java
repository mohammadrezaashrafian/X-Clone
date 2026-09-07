package logic_core.infrastructure.transport.http;

import com.google.gson.Gson;
import logic_core.app.dto.request.CreateTweetRequest;
import logic_core.app.dto.request.DeleteAccountRequest;
import logic_core.app.dto.request.RegisterRequest;
import logic_core.app.dto.request.RetweetRequest;
import logic_core.app.dto.request.UnretweetRequest;
import logic_core.app.dto.response.AuthResponse;
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
import org.springframework.dao.DataIntegrityViolationException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Core integration suite — Issue #6 schema-constraint regressions through the
 * real PostgreSQL database.
 *
 * <p>The Spring context boots exactly like production: Flyway applies
 * {@code V1__baseline.sql} and Hibernate {@code ddl-auto=validate} must pass
 * (the context would fail to start otherwise). The tests then verify the
 * Issue #6 schema contract at both the application layer and the database
 * layer:
 *
 * <ul>
 *   <li>active-only username/email uniqueness (partial unique indexes)</li>
 *   <li>soft-deleted identity reuse</li>
 *   <li>active retweet-marker uniqueness (partial unique index) with re-insert
 *       allowed after unretweet</li>
 *   <li>notification type CHECK constraint</li>
 * </ul>
 */
@SpringBootTest(classes = ServerMain.class)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatabaseConstraintIntegrationTest {

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
            System.err.println("DatabaseConstraintIntegrationTest cleanup warning: " + e.getMessage());
        }
    }

    // ========================================================================
    // Flyway + Hibernate validation oracle
    // ========================================================================

    @Test
    void flywayV1Baseline_applied_andIssue6SchemaObjectsPresent() {
        // The Spring context booting at all already proves Hibernate
        // ddl-auto=validate passed against the Flyway-created schema.
        Long applied = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = true",
                Long.class);
        assertThat(applied).isEqualTo(1L);

        Long activeOnlyIndexes = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE indexname IN ("
                        + "'uq_users_username_active', 'uq_users_email_active', 'uq_tweets_active_retweet')",
                Long.class);
        assertThat(activeOnlyIndexes)
                .as("Issue #6 partial unique indexes must exist")
                .isEqualTo(3L);

        Long notificationCheck = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_constraint WHERE conname = 'notifications_type_check'",
                Long.class);
        assertThat(notificationCheck).isEqualTo(1L);

        Long baselineTables = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name IN ("
                        + "'users','sessions','tweets','likes','follows','blocks','mutes',"
                        + "'conversations','conversation_members','direct_messages','media',"
                        + "'notifications','tweet_edits','hashtags','hashtag_follows',"
                        + "'tweet_hashtags','tweet_mentions','polls','poll_options','poll_votes')",
                Long.class);
        assertThat(baselineTables)
                .as("V1__baseline.sql must create all 13 entity tables and preserve the 7 legacy tables")
                .isEqualTo(20L);
    }

    // ========================================================================
    // Active-only username/email uniqueness
    // ========================================================================

    @Test
    void duplicateActiveUsername_andDuplicateActiveEmail_rejected() throws Exception {
        String username = "dbdu_" + UUID.randomUUID().toString().substring(0, 8);
        String email = username + "@dbconstraint.com";
        String password = "StrongPassword123!";

        AuthResponse first = register(username, email, "Display one");
        assertThat(countActiveUsersByUsername(username)).isEqualTo(1L);

        // Same username, different email -> conflict surfaced as a business failure.
        ResponseEnvelope sameUsername = sendRegister(username, "other-" + email, "Display two");
        assertThat(sameUsername.isSuccess()).isFalse();
        assertThat(sameUsername.errorCode()).isEqualTo("AUTH_REGISTER_FAILED");
        assertThat(sameUsername.errorMessage()).containsIgnoringCase("already taken");

        // Different username, same email -> conflict surfaced as a business failure.
        ResponseEnvelope sameEmail = sendRegister(username + "b", email, "Display three");
        assertThat(sameEmail.isSuccess()).isFalse();
        assertThat(sameEmail.errorCode()).isEqualTo("AUTH_REGISTER_FAILED");
        assertThat(sameEmail.errorMessage()).containsIgnoringCase("already registered");

        // Nothing was persisted by either failed registration.
        assertThat(countActiveUsersByUsername(username)).isEqualTo(1L);
        createdUserIds.add(first.userId());
    }

    @Test
    void softDeletedUsernameAndEmail_reusableByNewAccount() throws Exception {
        String username = "dbdel_" + UUID.randomUUID().toString().substring(0, 8);
        String email = username + "@dbconstraint.com";
        String password = "StrongPassword123!";

        AuthResponse original = register(username, email, "Original display");
        UUID originalId = original.userId();

        // Soft-delete the account through the application (needs its password).
        ResponseEnvelope deleteEnvelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.USER_DELETE_ACCOUNT,
                gson.toJsonTree(new DeleteAccountRequest(originalId, original.token(), password)),
                null));
        assertThat(deleteEnvelope.isSuccess())
                .as("delete account must succeed — errorCode=%s, errorMessage=%s",
                        deleteEnvelope.errorCode(), deleteEnvelope.errorMessage())
                .isTrue();

        assertThat(countDeletedUsersByUsername(username)).isEqualTo(1L);
        assertThat(countActiveUsersByUsername(username)).isZero();

        // The same username AND email become available for a fresh active account.
        AuthResponse replacement = register(username, email, "Replacement display");
        assertThat(replacement.userId()).isNotEqualTo(originalId);

        // Both rows coexist: one soft-deleted, one active (partial unique index).
        assertThat(countUsersByUsername(username)).isEqualTo(2L);
        assertThat(countActiveUsersByUsername(username)).isEqualTo(1L);
        assertThat(countDeletedUsersByUsername(username)).isEqualTo(1L);
    }

    // ========================================================================
    // Active retweet-marker uniqueness
    // ========================================================================

    @Test
    void duplicateRetweet_applicationLevelRejected() throws Exception {
        AuthResponse author = registerUnique("dbara");
        AuthResponse retweeter = registerUnique("dbrb");

        TweetResponse tweet = createTweet(author, "duplicate-retweet-1");
        TweetResponse retweetRow = retweet(retweeter, tweet.id());
        assertThat(retweetRow.id()).isNotEqualTo(tweet.id());
        assertThat(countActiveRetweetRows(tweet.id(), retweeter.userId())).isEqualTo(1L);

        ResponseEnvelope duplicate = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_RETWEET,
                gson.toJsonTree(new RetweetRequest(tweet.id(), retweeter.token())),
                null));
        assertThat(duplicate.isSuccess()).isFalse();
        assertThat(duplicate.errorCode()).isEqualTo("TWEET_RETWEET_FAILED");
        assertThat(duplicate.errorMessage()).containsIgnoringCase("already retweeted");

        assertThat(countActiveRetweetRows(tweet.id(), retweeter.userId())).isEqualTo(1L);
    }

    @Test
    void duplicateActiveRetweetMarker_databaseConstraintRejects_andReinsertAllowedAfterUnretweet()
            throws Exception {
        AuthResponse author = registerUnique("dbca");
        AuthResponse retweeter = registerUnique("dbcb");

        TweetResponse tweet = createTweet(author, "duplicate-retweet-2");
        TweetResponse retweetRow = retweet(retweeter, tweet.id());

        // Directly attempt a second active marker row for the same
        // (author, retweet_of) pair: the partial unique index must reject it.
        UUID smuggledRowId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO tweets (id, created_at, updated_at, published_at, author_id, retweet_of_id) "
                        + "VALUES (?, now(), now(), now(), ?, ?)",
                smuggledRowId,
                retweeter.userId(),
                tweet.id()))
                .as("uq_tweets_active_retweet must reject a duplicate active marker")
                .isInstanceOf(DataIntegrityViolationException.class);

        // Unretweet through the application removes the marker row...
        ResponseEnvelope unretweet = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_UNRETWEET,
                gson.toJsonTree(new UnretweetRequest(tweet.id(), retweeter.token())),
                null));
        assertThat(unretweet.isSuccess())
                .as("unretweet must succeed — errorCode=%s, errorMessage=%s",
                        unretweet.errorCode(), unretweet.errorMessage())
                .isTrue();
        assertThat(countActiveRetweetRows(tweet.id(), retweeter.userId())).isZero();

        // ...and the same (author, retweet_of) pair can be inserted again: the
        // uniqueness is scoped to active rows only.
        UUID newRowId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tweets (id, created_at, updated_at, published_at, author_id, retweet_of_id) "
                        + "VALUES (?, now(), now(), now(), ?, ?)",
                newRowId,
                retweeter.userId(),
                tweet.id());
        createdTweetIds.add(newRowId);
        assertThat(countActiveRetweetRows(tweet.id(), retweeter.userId())).isEqualTo(1L);
    }

    // ========================================================================
    // Notification type CHECK constraint
    // ========================================================================

    @Test
    void invalidNotificationType_databaseCheckRejects() throws Exception {
        AuthResponse author = registerUnique("dbna");
        AuthResponse actor = registerUnique("dbnb");

        TweetResponse tweet = createTweet(author, "notif-type-1");

        // An application-supported type persists fine.
        jdbcTemplate.update(
                "INSERT INTO notifications (id, created_at, recipient_id, actor_id, tweet_id, type) "
                        + "VALUES (?, now(), ?, ?, ?, 'LIKE')",
                UUID.randomUUID(),
                author.userId(),
                actor.userId(),
                tweet.id());

        // MENTION is outside the application contract and must be rejected by
        // the CHECK constraint (no mention feature exists yet).
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO notifications (id, created_at, recipient_id, actor_id, tweet_id, type) "
                        + "VALUES (?, now(), ?, ?, ?, 'MENTION')",
                UUID.randomUUID(),
                author.userId(),
                actor.userId(),
                tweet.id()))
                .as("notifications_type_check must reject the legacy MENTION superset type")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private AuthResponse registerUnique(String prefix) throws Exception {
        String username = prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
        return register(username, username + "@dbconstraint.com", "Display " + prefix);
    }

    private AuthResponse register(String username, String email, String displayName) throws Exception {
        ResponseEnvelope envelope = sendRegister(username, email, displayName);
        assertSuccess(envelope, "register " + username);

        AuthResponse auth = gson.fromJson(envelope.getData(), AuthResponse.class);
        createdUserIds.add(auth.userId());
        return auth;
    }

    private ResponseEnvelope sendRegister(String username, String email, String displayName) throws Exception {
        RegisterRequest registerRequest =
                new RegisterRequest(username, email, "StrongPassword123!", displayName);
        return send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.AUTH_REGISTER,
                gson.toJsonTree(registerRequest),
                null));
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

    private TweetResponse retweet(AuthResponse actor, UUID tweetId) throws Exception {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_RETWEET,
                gson.toJsonTree(new RetweetRequest(tweetId, actor.token())),
                null));
        assertSuccess(envelope, "retweet " + tweetId);

        TweetResponse retweetRow = gson.fromJson(envelope.getData(), TweetResponse.class);
        createdTweetIds.add(retweetRow.id());
        return retweetRow;
    }

    private long countUsersByUsername(String username) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username = ?",
                Long.class,
                username);
        return count == null ? 0L : count;
    }

    private long countActiveUsersByUsername(String username) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username = ? AND is_deleted = false",
                Long.class,
                username);
        return count == null ? 0L : count;
    }

    private long countDeletedUsersByUsername(String username) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username = ? AND is_deleted = true",
                Long.class,
                username);
        return count == null ? 0L : count;
    }

    private long countActiveRetweetRows(UUID originalTweetId, UUID userId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tweets WHERE retweet_of_id = ? AND author_id = ? AND is_deleted = false",
                Long.class,
                originalTweetId,
                userId);
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
