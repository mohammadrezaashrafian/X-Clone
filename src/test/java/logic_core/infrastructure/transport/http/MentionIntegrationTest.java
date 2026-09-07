package logic_core.infrastructure.transport.http;

import com.google.gson.Gson;
import logic_core.app.dto.request.CreateTweetRequest;
import logic_core.app.dto.request.DeleteAccountRequest;
import logic_core.app.dto.request.DeleteTweetRequest;
import logic_core.app.dto.request.RegisterRequest;
import logic_core.app.dto.request.ReplyTweetRequest;
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
 * Core integration suite — mention system.
 *
 * <p>Drives the full stack ({@code POST /api} → {@code RequestDispatcher} →
 * Facade → UseCase → Repository Adapter → JPA → PostgreSQL) for the V2.1
 * mention feature: extraction of {@code @username} tokens from tweet content,
 * resolution against real (active, non-deleted) users, deduplication,
 * persistence into the {@code tweet_mentions} table, reply integration, and
 * cleanup when a tweet is deleted.
 *
 * <p>Uses the same real-PostgreSQL harness as the other HTTP integration
 * tests: the Spring context boots against {@code xclonedb} (Flyway applies
 * {@code V1__baseline.sql}, Hibernate {@code ddl-auto=validate} passes) and
 * every row created here is removed in {@link #cleanUpCreatedRows()}.
 */
@SpringBootTest(classes = ServerMain.class)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MentionIntegrationTest
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

                // tweet_mentions rows cascade on tweet/user deletion; delete
                // explicitly first so nothing lingers even if tweet deletion
                // is skipped by a failing assertion.
                jdbcTemplate.update(
                        "DELETE FROM tweet_mentions WHERE tweet_id IN (" + placeholders + ")",
                        tweetArgs);
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
            System.err.println("MentionIntegrationTest cleanup warning: " + e.getMessage());
        }
    }

    // ========================================================================
    // Extraction + resolution + persistence
    // ========================================================================

    @Test
    void tweetCreate_resolvesAndPersistsMentions() throws Exception
    {
        AuthResponse alice = registerUser("mena", "Alice");
        AuthResponse bob = registerUser("menb", "Bob");
        AuthResponse author = registerUser("menc", "Carl");

        TweetResponse tweet = createTweet(
                author,
                "hey @" + alice.username() + " and @" + bob.username() + " welcome!");

        assertThat(mentionUserIds(tweet.id()))
                .containsExactlyInAnyOrder(alice.userId(), bob.userId());
    }

    @Test
    void duplicateMentions_collapseToSingleRelationship() throws Exception
    {
        AuthResponse bob = registerUser("mend", "Bob");
        AuthResponse author = registerUser("mene", "Eve");

        TweetResponse tweet = createTweet(
                author,
                "@" + bob.username() + " @" + bob.username() + " @" + bob.username() + "!");

        assertThat(mentionUserIds(tweet.id()))
                .containsExactly(bob.userId());
    }

    @Test
    void unknownAndInvalidUsernames_areSkipped() throws Exception
    {
        AuthResponse author = registerUser("menf", "Frank");

        // "nobody" does not exist; "ab" is too short to be a username; the
        // "@example" token in the email resolves to no user.
        TweetResponse tweet = createTweet(
                author,
                "hello @nobody and @ab — write to admin@example.com");

        assertThat(mentionUserIds(tweet.id())).isEmpty();
    }

    @Test
    void mentionResolution_isCaseSensitiveLikeRegistration() throws Exception
    {
        // Usernames are stored verbatim at registration, so mention resolution
        // matches the exact case.
        AuthResponse mixedCase = registerUserWithUsername(
                "MiXeD" + UUID.randomUUID().toString().substring(0, 6),
                "Mixed");
        AuthResponse author = registerUser("menh", "Hank");

        // Exact-case mention resolves.
        TweetResponse exact = createTweet(author, "ping @" + mixedCase.username());
        assertThat(mentionUserIds(exact.id())).containsExactly(mixedCase.userId());

        // Different case does not resolve.
        TweetResponse differentCase = createTweet(
                author,
                "ping @" + mixedCase.username().toLowerCase());
        assertThat(mentionUserIds(differentCase.id())).isEmpty();
    }

    @Test
    void softDeletedUser_isNotMentionable() throws Exception
    {
        AuthResponse ghost = registerUser("meni", "Ghost");
        AuthResponse author = registerUser("menj", "Ivy");

        deleteAccount(ghost);

        TweetResponse tweet = createTweet(author, "shoutout @" + ghost.username());
        assertThat(mentionUserIds(tweet.id())).isEmpty();
    }

    // ========================================================================
    // Reply integration
    // ========================================================================

    @Test
    void reply_persistsMentions() throws Exception
    {
        AuthResponse bob = registerUser("menk", "Bob");
        AuthResponse author = registerUser("menl", "Laura");

        TweetResponse parent = createTweet(author, "parent without mentions");
        TweetResponse reply = reply(
                author,
                parent.id(),
                "@" + bob.username() + " here is the answer");

        assertThat(mentionUserIds(reply.id())).containsExactly(bob.userId());
        // Parent tweet has no mention rows.
        assertThat(mentionUserIds(parent.id())).isEmpty();
    }

    // ========================================================================
    // Deletion cleanup
    // ========================================================================

    @Test
    void deletingTweet_removesItsMentionRows() throws Exception
    {
        AuthResponse bob = registerUser("menm", "Bob");
        AuthResponse author = registerUser("menn", "Nina");

        TweetResponse tweet = createTweet(
                author,
                "@" + bob.username() + " will vanish");
        assertThat(mentionUserIds(tweet.id())).containsExactly(bob.userId());

        deleteTweet(tweet.id(), author.token());

        assertThat(mentionUserIds(tweet.id())).isEmpty();
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private AuthResponse registerUser(String prefix, String displayName) throws Exception
    {
        String username = prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
        return registerUserWithUsername(username, displayName);
    }

    private AuthResponse registerUserWithUsername(String username, String displayName)
            throws Exception
    {
        RegisterRequest registerRequest = new RegisterRequest(
                username,
                username.toLowerCase() + "@mentiontest.com",
                "StrongPassword123!",
                displayName);

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

    private void deleteAccount(AuthResponse user) throws Exception
    {
        DeleteAccountRequest deleteRequest = new DeleteAccountRequest(
                user.userId(),
                user.token(),
                "StrongPassword123!");
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.USER_DELETE_ACCOUNT,
                gson.toJsonTree(deleteRequest),
                null));
        assertSuccess(envelope, "delete account " + user.userId());
    }

    private List<UUID> mentionUserIds(UUID tweetId)
    {
        return jdbcTemplate.queryForList(
                "SELECT mentioned_user_id FROM tweet_mentions WHERE tweet_id = ?",
                UUID.class,
                tweetId);
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