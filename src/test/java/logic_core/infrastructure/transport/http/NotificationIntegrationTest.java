package logic_core.infrastructure.transport.http;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import logic_core.app.dto.request.CountUnreadNotificationsRequest;
import logic_core.app.dto.request.CreateTweetRequest;
import logic_core.app.dto.request.FollowUserRequest;
import logic_core.app.dto.request.GetNotificationsRequest;
import logic_core.app.dto.request.LikeTweetRequest;
import logic_core.app.dto.request.ReadNotificationRequest;
import logic_core.app.dto.request.ReadAllNotificationsRequest;
import logic_core.app.dto.request.ReplyTweetRequest;
import logic_core.app.dto.request.RegisterRequest;
import logic_core.app.dto.request.RetweetRequest;
import logic_core.app.dto.request.UnfollowUserRequest;
import logic_core.app.dto.request.UnlikeTweetRequest;
import logic_core.app.dto.request.UnretweetRequest;
import logic_core.app.dto.response.AuthResponse;
import logic_core.app.dto.response.GetNotificationsPageResponse;
import logic_core.app.dto.response.NotificationResponse;
import logic_core.app.dto.response.TweetResponse;
import logic_core.app.dto.response.UnreadNotificationsCountResponse;
import logic_core.domain.model.notification.NotificationType;
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
import org.springframework.jdbc.core.JdbcTemplate;
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
 * Integration suite for V2.1 #7 advanced notifications.
 *
 * <p>Drives the real application path over {@code POST /api}: interaction
 * use cases (create / like / unlike / follow / unfollow / retweet /
 * unretweet / reply) generate, retract, and surface notifications through
 * {@code NOTIFICATION_GET} (paginated), {@code NOTIFICATION_GET_UNREAD_COUNT},
 * {@code NOTIFICATION_READ}, and {@code NOTIFICATION_READ_ALL}.
 *
 * <p>Uses the same disposable-test-body cleanup pattern as the other HTTP
 * integration tests.
 */
@SpringBootTest(classes = ServerMain.class)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationIntegrationTest
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

    // ========================================================================
    // MENTION notifications
    // ========================================================================

    @Test
    void mentionNotification_generatedForEachMentionedUser() throws Exception
    {
        AuthResponse author = registerUser("notif");
        AuthResponse alice = registerUser("notif");
        AuthResponse charlie = registerUser("notif");

        createTweet(
                author,
                "hello @" + alice.username() + " and @"
                        + charlie.username() + " and @ghost_user_no_exist",
                null,
                null);

        List<NotificationResponse> aliceNotifications =
                getNotifications(alice).notifications();
        assertThat(aliceNotifications).hasSize(1);
        assertThat(aliceNotifications.get(0).type())
                .isEqualTo(NotificationType.MENTION);
        assertThat(aliceNotifications.get(0).actor().userId())
                .isEqualTo(author.userId());
        assertThat(aliceNotifications.get(0).tweetId()).isNotNull();
        assertThat(aliceNotifications.get(0).read()).isFalse();

        List<NotificationResponse> charlieNotifications =
                getNotifications(charlie).notifications();
        assertThat(charlieNotifications).hasSize(1);
        assertThat(charlieNotifications.get(0).type())
                .isEqualTo(NotificationType.MENTION);

        // The author is not notified about mentioning other users, and the
        // unknown mention resolves to nobody.
        assertThat(getNotifications(author).notifications()).isEmpty();

        // Mention rows are one-per-user even though both users were mentioned
        // in the same tweet; each mentioned user appears once.
        Long mentionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tweet_mentions WHERE tweet_id IN ("
                        + "SELECT id FROM tweets WHERE author_id = ?)",
                Long.class,
                author.userId());
        assertThat(mentionRows).isEqualTo(2L);
    }

    @Test
    void mentionNotification_skippedForSelfMention() throws Exception
    {
        AuthResponse author = registerUser("notif");

        createTweet(author, "note to self @" + author.username(), null, null);

        assertThat(getNotifications(author).notifications()).isEmpty();
    }

    // ========================================================================
    // Unread count lifecycle
    // ========================================================================

    @Test
    void unreadCount_reflectsInteractionsAndReadState() throws Exception
    {
        AuthResponse author = registerUser("notif");
        AuthResponse actor = registerUser("notif");

        assertThat(getUnreadCount(author)).isZero();

        TweetResponse tweet = createTweet(author, "count me", null, null);
        like(actor, tweet.id());
        assertThat(getUnreadCount(author)).isEqualTo(1L);

        reply(actor, tweet.id(), "a reply");
        assertThat(getUnreadCount(author)).isEqualTo(2L);

        follow(actor, author);
        assertThat(getUnreadCount(author)).isEqualTo(3L);

        markAllRead(author);
        assertThat(getUnreadCount(author)).isZero();

        // Undoing an interaction after everything was read keeps count at 0.
        unlike(actor, tweet.id());
        assertThat(getUnreadCount(author)).isZero();
    }

    // ========================================================================
    // Retraction on undo
    // ========================================================================

    @Test
    void likeThenUnlike_removesLikeNotification() throws Exception
    {
        AuthResponse author = registerUser("notif");
        AuthResponse actor = registerUser("notif");

        TweetResponse tweet = createTweet(author, "like me", null, null);

        like(actor, tweet.id());
        List<NotificationResponse> notifications =
                getNotifications(author).notifications();
        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0).type()).isEqualTo(NotificationType.LIKE);
        assertThat(notifications.get(0).actor().userId())
                .isEqualTo(actor.userId());

        unlike(actor, tweet.id());
        assertThat(getNotifications(author).notifications()).isEmpty();

        // Re-liking generates a fresh notification.
        like(actor, tweet.id());
        List<NotificationResponse> reLiked =
                getNotifications(author).notifications();
        assertThat(reLiked).hasSize(1);
        assertThat(reLiked.get(0).type()).isEqualTo(NotificationType.LIKE);
    }

    @Test
    void followThenUnfollow_removesFollowNotification() throws Exception
    {
        AuthResponse followee = registerUser("notif");
        AuthResponse follower = registerUser("notif");

        follow(follower, followee);
        List<NotificationResponse> notifications =
                getNotifications(followee).notifications();
        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0).type())
                .isEqualTo(NotificationType.FOLLOW);
        assertThat(notifications.get(0).actor().userId())
                .isEqualTo(follower.userId());

        unfollow(follower, followee);
        assertThat(getNotifications(followee).notifications()).isEmpty();
    }

    @Test
    void retweetThenUnretweet_removesRetweetNotification() throws Exception
    {
        AuthResponse author = registerUser("notif");
        AuthResponse actor = registerUser("notif");

        TweetResponse tweet = createTweet(author, "retweet me", null, null);

        retweet(actor, tweet.id());
        List<NotificationResponse> notifications =
                getNotifications(author).notifications();
        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0).type())
                .isEqualTo(NotificationType.RETWEET);

        unretweet(actor, tweet.id());
        assertThat(getNotifications(author).notifications()).isEmpty();
    }

    // ========================================================================
    // Pagination
    // ========================================================================

    @Test
    void getNotifications_isPaginated() throws Exception
    {
        AuthResponse author = registerUser("notif");

        TweetResponse tweet = createTweet(author, " paginate ", null, null);

        for (int i = 0; i < 5; i++)
        {
            like(registerUser("notif"), tweet.id());
        }

        GetNotificationsPageResponse page0 =
                getNotifications(author.token(), 0, 2);
        assertThat(page0.totalItems()).isEqualTo(5L);
        assertThat(page0.notifications()).hasSize(2);
        assertThat(page0.page()).isZero();
        assertThat(page0.pageSize()).isEqualTo(2);
        assertThat(page0.hasNext()).isTrue();

        GetNotificationsPageResponse page1 =
                getNotifications(author.token(), 1, 2);
        assertThat(page1.notifications()).hasSize(2);
        assertThat(page1.hasNext()).isTrue();

        GetNotificationsPageResponse page2 =
                getNotifications(author.token(), 2, 2);
        assertThat(page2.notifications()).hasSize(1);
        assertThat(page2.hasNext()).isFalse();

        // Legacy request without pagination defaults to page 0 with a
        // server-side default page size.
        GetNotificationsPageResponse defaultPage =
                getNotifications(author);
        assertThat(defaultPage.page()).isZero();
        assertThat(defaultPage.notifications()).hasSize(5);
    }

    // ========================================================================
    // Ownership + auth
    // ========================================================================

    @Test
    void readNotification_byNonOwner_fails() throws Exception
    {
        AuthResponse author = registerUser("notif");
        AuthResponse stranger = registerUser("notif");

        TweetResponse tweet = createTweet(author, "notify me", null, null);
        like(stranger, tweet.id());

        UUID notificationId =
                getNotifications(author).notifications().get(0).id();

        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.NOTIFICATION_READ,
                gson.toJsonTree(new ReadNotificationRequest(
                        notificationId,
                        stranger.token())),
                null));

        assertThat(envelope.isSuccess()).isFalse();
        assertThat(envelope.errorCode()).isEqualTo("NOTIFICATION_READ_FAILED");
    }

    @Test
    void getNotifications_requiresAuthentication() throws Exception
    {
        mockMvc.perform(post("/api")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(gson.toJson(new RequestEnvelope(
                                UUID.randomUUID(),
                                RequestType.NOTIFICATION_GET,
                                gson.toJsonTree(new GetNotificationsRequest(null)),
                                null))))
                .andExpect(status().isUnauthorized());
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private AuthResponse registerUser(String prefix) throws Exception
    {
        String username = prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest registerRequest = new RegisterRequest(
                username,
                username + "@notiftest.com",
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

    private TweetResponse createTweet(
            AuthResponse author,
            String content,
            String hashtagHint,
            List<String> mediaIdStrs) throws Exception
    {
        CreateTweetRequest createRequest = new CreateTweetRequest(
                content,
                null,
                null,
                null,
                author.token(),
                mediaIdStrs,
                null);
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

    private TweetResponse reply(AuthResponse replier, UUID parentTweetId, String text)
            throws Exception
    {
        ReplyTweetRequest replyRequest = new ReplyTweetRequest(
                parentTweetId,
                text,
                null,
                replier.token());
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

    private void like(AuthResponse actor, UUID tweetId) throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_LIKE,
                gson.toJsonTree(new LikeTweetRequest(tweetId, actor.token())),
                null));
        assertSuccess(envelope, "like " + tweetId);
    }

    private void unlike(AuthResponse actor, UUID tweetId) throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_UNLIKE,
                gson.toJsonTree(new UnlikeTweetRequest(tweetId, actor.token())),
                null));
        assertSuccess(envelope, "unlike " + tweetId);
    }

    private void follow(AuthResponse follower, AuthResponse followee) throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.RELATION_FOLLOW,
                gson.toJsonTree(new FollowUserRequest(
                        followee.userId(),
                        follower.token())),
                null));
        assertSuccess(envelope, "follow");
    }

    private void unfollow(AuthResponse follower, AuthResponse followee) throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.RELATION_UNFOLLOW,
                gson.toJsonTree(new UnfollowUserRequest(
                        followee.userId(),
                        follower.token())),
                null));
        assertSuccess(envelope, "unfollow");
    }

    private void retweet(AuthResponse actor, UUID tweetId) throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_RETWEET,
                gson.toJsonTree(new RetweetRequest(tweetId, actor.token())),
                null));
        assertSuccess(envelope, "retweet " + tweetId);
    }

    private void unretweet(AuthResponse actor, UUID tweetId) throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_UNRETWEET,
                gson.toJsonTree(new UnretweetRequest(tweetId, actor.token())),
                null));
        assertSuccess(envelope, "unretweet " + tweetId);
    }

    private GetNotificationsPageResponse getNotifications(AuthResponse user)
            throws Exception
    {
        return getNotifications(user.token(), null, null);
    }

    private GetNotificationsPageResponse getNotifications(
            String token, Integer page, Integer pageSize) throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.NOTIFICATION_GET,
                gson.toJsonTree(new GetNotificationsRequest(token, page, pageSize)),
                null));
        assertSuccess(envelope, "get notifications");
        return gson.fromJson(envelope.getData(), GetNotificationsPageResponse.class);
    }

    private long getUnreadCount(AuthResponse user) throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.NOTIFICATION_GET_UNREAD_COUNT,
                gson.toJsonTree(new CountUnreadNotificationsRequest(user.token())),
                null));
        assertSuccess(envelope, "unread count");
        return gson.fromJson(
                envelope.getData(),
                UnreadNotificationsCountResponse.class).unreadCount();
    }

    private void markAllRead(AuthResponse user) throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.NOTIFICATION_READ_ALL,
                gson.toJsonTree(new ReadAllNotificationsRequest(user.token())),
                null));
        assertSuccess(envelope, "mark all read");
    }

    private ResponseEnvelope send(RequestEnvelope request) throws Exception
    {
        org.springframework.mock.web.MockHttpServletResponse response =
                mockMvc.perform(post("/api")
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(gson.toJson(request)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse();

        ResponseEnvelope envelope = gson.fromJson(
                response.getContentAsString(),
                ResponseEnvelope.class);
        assertThat(envelope).isNotNull();
        return envelope;
    }

    private void assertSuccess(ResponseEnvelope envelope, String step)
    {
        assertThat(envelope.isSuccess())
                .as("%s — errorCode=%s, errorMessage=%s",
                        step, envelope.errorCode(), envelope.errorMessage())
                .isTrue();
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

    @AfterEach
    void cleanUpCreatedRows()
    {
        try
        {
            if (!createdTweetIds.isEmpty())
            {
                String tweetPlaceholders = repeatPlaceholders(createdTweetIds.size());
                Object[] tweetArgs = createdTweetIds.toArray();

                jdbcTemplate.update(
                        "DELETE FROM notifications WHERE tweet_id IN ("
                                + tweetPlaceholders + ")",
                        tweetArgs);
                jdbcTemplate.update(
                        "DELETE FROM likes WHERE tweet_id IN ("
                                + tweetPlaceholders + ")",
                        tweetArgs);
                jdbcTemplate.update(
                        "DELETE FROM tweet_mentions WHERE tweet_id IN ("
                                + tweetPlaceholders + ")",
                        tweetArgs);
                jdbcTemplate.update(
                        "DELETE FROM tweets WHERE id IN ("
                                + tweetPlaceholders + ")",
                        tweetArgs);
            }
            if (!createdUserIds.isEmpty())
            {
                String userPlaceholders = repeatPlaceholders(createdUserIds.size());
                Object[] userArgs = createdUserIds.toArray();

                jdbcTemplate.update(
                        "DELETE FROM notifications WHERE recipient_id IN ("
                                + userPlaceholders + ")"
                                + " OR actor_id IN (" + userPlaceholders + ")",
                        doubleUserArgs());
                jdbcTemplate.update(
                        "DELETE FROM follows WHERE follower_id IN ("
                                + userPlaceholders + ")"
                                + " OR following_id IN (" + userPlaceholders + ")",
                        doubleUserArgs());
                jdbcTemplate.update(
                        "DELETE FROM sessions WHERE user_id IN ("
                                + userPlaceholders + ")",
                        userArgs);
                jdbcTemplate.update(
                        "DELETE FROM users WHERE id IN ("
                                + userPlaceholders + ")",
                        userArgs);
            }
        }
        catch (Exception e)
        {
            System.err.println("NotificationIntegrationTest cleanup warning: "
                    + e.getMessage());
        }
    }

    private Object[] doubleUserArgs()
    {
        Object[] userArgs = createdUserIds.toArray();
        Object[] doubleArgs = new Object[createdUserIds.size() * 2];
        System.arraycopy(userArgs, 0, doubleArgs, 0, createdUserIds.size());
        System.arraycopy(userArgs, 0, doubleArgs, createdUserIds.size(),
                createdUserIds.size());
        return doubleArgs;
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
}
