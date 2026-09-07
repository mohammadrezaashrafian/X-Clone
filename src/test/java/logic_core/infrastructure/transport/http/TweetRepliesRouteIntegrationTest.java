package logic_core.infrastructure.transport.http;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import logic_core.app.dto.request.BlockUserRequest;
import logic_core.app.dto.request.CreateTweetRequest;
import logic_core.app.dto.request.DeleteTweetRequest;
import logic_core.app.dto.request.FollowUserRequest;
import logic_core.app.dto.request.GetNotificationsRequest;
import logic_core.app.dto.request.GetRepliesRequest;
import logic_core.app.dto.request.GetTweetRequest;
import logic_core.app.dto.request.LikeTweetRequest;
import logic_core.app.dto.request.ReadAllNotificationsRequest;
import logic_core.app.dto.request.ReadNotificationRequest;
import logic_core.app.dto.request.RegisterRequest;
import logic_core.app.dto.request.ReplyTweetRequest;
import logic_core.app.dto.request.RetweetRequest;
import logic_core.app.dto.request.UnlikeTweetRequest;
import logic_core.app.dto.request.UnretweetRequest;
import logic_core.app.dto.response.AuthResponse;
import logic_core.app.dto.response.NotificationResponse;
import logic_core.app.dto.response.TweetResponse;
import logic_core.app.dto.timeline.TimelineTweet;
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
 * Integration tests for the tweet read transport routes.
 *
 * <p>TWEET_GET_REPLIES (previously a stub that returned {@code null}):
 *
 * <pre>
 * register A, B
 *   → A creates parent tweet
 *   → B replies twice
 *   → TWEET_GET_REPLIES → success envelope whose payload is a JSON array of
 *     {@link TimelineTweet} that the existing client DTO can deserialize
 * </pre>
 *
 * <p>TWEET_GET (single active tweet retrieval): drives the same full stack through
 * {@code POST /api} to verify an authenticated user can read an active tweet with
 * server-authoritative counts, that missing/deleted tweets and block-hidden
 * content surface as failures (not leaks), and that unauthenticated access is
 * rejected with a failure envelope.
 */
@SpringBootTest(classes = ServerMain.class)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TweetRepliesRouteIntegrationTest {

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

                // Blocks normally cascade with the user rows (FK ON DELETE CASCADE);
                // explicit cleanup is belt-and-braces for schemas without it.
                try {
                    Object[] doubleArgs = new Object[createdUserIds.size() * 2];
                    System.arraycopy(userArgs, 0, doubleArgs, 0, createdUserIds.size());
                    System.arraycopy(userArgs, 0, doubleArgs, createdUserIds.size(), createdUserIds.size());
                    jdbcTemplate.update(
                            "DELETE FROM blocks WHERE blocker_id IN (" + placeholders + ")" +
                                    " OR blocked_id IN (" + placeholders + ")",
                            doubleArgs);
                } catch (Exception ignored) {
                    // Best-effort only; user deletion below remains authoritative.
                }

                // Notifications normally cascade with the recipient user rows
                // (FK ON DELETE CASCADE); explicit cleanup is belt-and-braces.
                try {
                    jdbcTemplate.update(
                            "DELETE FROM notifications WHERE recipient_id IN (" + placeholders + ")",
                            userArgs);
                } catch (Exception ignored) {
                    // Best-effort only; user deletion below remains authoritative.
                }

                jdbcTemplate.update(
                        "DELETE FROM sessions WHERE user_id IN (" + placeholders + ")", userArgs);
                jdbcTemplate.update(
                        "DELETE FROM users WHERE id IN (" + placeholders + ")", userArgs);
            }
        } catch (Exception e) {
            System.err.println("TweetRepliesRouteIntegrationTest cleanup warning: " + e.getMessage());
        }
    }

    @Test
    void replies_authenticatedParentAuthor_seesRepliesOldestFirst() throws Exception {
        AuthResponse userA = registerUser("repa");
        AuthResponse userB = registerUser("repb");

        TweetResponse parent = createTweet(userA, "reply-parent-1");

        TweetResponse first = reply(userB, parent.id(), "reply-first");
        TweetResponse second = reply(userB, parent.id(), "reply-second");

        ResponseEnvelope envelope = sendReplies(parent.id(), userA.token());
        assertSuccess(envelope, "parent author fetches replies");

        List<TimelineTweet> replies = gson.fromJson(
                envelope.getData(),
                TypeToken.getParameterized(List.class, TimelineTweet.class).getType());

        assertThat(replies)
                .as("reply thread must contain both replies in oldest-first order")
                .extracting(TimelineTweet::content)
                .containsExactly("reply-first", "reply-second");
        assertThat(replies)
                .allSatisfy(r -> {
                    assertThat(r.authorId()).isEqualTo(userB.userId());
                    assertThat(r.tweetId()).isNotNull();
                    assertThat(r.username()).isNotBlank();
                    assertThat(r.publishedAt()).isNotNull();
                });
        assertThat(replies).extracting(TimelineTweet::tweetId)
                .contains(first.id(), second.id());
    }

    @Test
    void replies_authenticatedOtherUser_canReadPublicThread() throws Exception {
        AuthResponse userA = registerUser("repc");
        AuthResponse userB = registerUser("repd");
        AuthResponse userC = registerUser("repe");

        TweetResponse parent = createTweet(userA, "reply-parent-2");
        reply(userB, parent.id(), "reply-third");

        // C is not involved in the thread but holds a valid session: public
        // tweet threads stay readable (same posture as timelines).
        ResponseEnvelope envelope = sendReplies(parent.id(), userC.token());
        assertSuccess(envelope, "unrelated authenticated user reads public thread");

        List<TimelineTweet> replies = gson.fromJson(
                envelope.getData(),
                TypeToken.getParameterized(List.class, TimelineTweet.class).getType());
        assertThat(replies).extracting(TimelineTweet::content).containsExactly("reply-third");
    }

    @Test
    void replies_unauthenticated_rejectedWithUnauthorized() throws Exception {
        AuthResponse userA = registerUser("repf");
        TweetResponse parent = createTweet(userA, "reply-parent-3");

        // Missing credentials on a protected route are now rejected by the HTTP
        // authentication layer with 401 Unauthorized and a failure envelope.
        ResponseEnvelope envelope = sendUnauthorized(
                new RequestEnvelope(
                        UUID.randomUUID(),
                        RequestType.TWEET_GET_REPLIES,
                        gson.toJsonTree(new GetRepliesRequest(parent.id(), null)),
                        null));
        assertThat(envelope)
                .as("route must return a failure envelope, not null")
                .isNotNull();
        assertThat(envelope.isSuccess()).isFalse();
        assertThat(envelope.errorCode()).isEqualTo("AUTH_REQUIRED");
    }

    @Test
    void replies_noReplies_returnsEmptyArray() throws Exception {
        AuthResponse userA = registerUser("repg");
        TweetResponse parent = createTweet(userA, "reply-parent-4");

        ResponseEnvelope envelope = sendReplies(parent.id(), userA.token());
        assertSuccess(envelope, "empty thread fetch");

        List<TimelineTweet> replies = gson.fromJson(
                envelope.getData(),
                TypeToken.getParameterized(List.class, TimelineTweet.class).getType());
        assertThat(replies).isEmpty();
    }

    // ========================================================================
    // TWEET_GET – single tweet retrieval
    // ========================================================================

    @Test
    void singleTweet_authenticatedUser_canReadActiveTweet() throws Exception {
        AuthResponse userA = registerUser("geta");
        AuthResponse userB = registerUser("getb");

        TweetResponse tweet = createTweet(userA, "single-tweet-1");

        ResponseEnvelope envelope = sendGetTweet(tweet.id(), userB.token());
        assertSuccess(envelope, "unrelated authenticated user fetches single tweet");

        TimelineTweet single = gson.fromJson(envelope.getData(), TimelineTweet.class);
        assertThat(single).isNotNull();
        assertThat(single.tweetId()).isEqualTo(tweet.id());
        assertThat(single.authorId()).isEqualTo(userA.userId());
        assertThat(single.username()).isNotBlank();
        assertThat(single.displayName()).isNotBlank();
        assertThat(single.content()).isEqualTo("single-tweet-1");
        assertThat(single.likeCount()).isZero();
        assertThat(single.replyCount()).isZero();
        assertThat(single.retweetCount()).isZero();
        assertThat(single.publishedAt()).isNotNull();
    }

    @Test
    void singleTweet_countsReflectServerState() throws Exception {
        AuthResponse userA = registerUser("getc");
        AuthResponse userB = registerUser("getd");

        TweetResponse tweet = createTweet(userA, "single-tweet-2");
        like(userB, tweet.id());
        reply(userB, tweet.id(), "single-tweet-reply");

        ResponseEnvelope envelope = sendGetTweet(tweet.id(), userB.token());
        assertSuccess(envelope, "tweet with interactions fetched");

        TimelineTweet single = gson.fromJson(envelope.getData(), TimelineTweet.class);
        // Server-authoritative counts are the interaction state the response
        // model exposes today (TimelineTweet carries no resolved viewer flag).
        assertThat(single.likeCount()).isEqualTo(1L);
        assertThat(single.replyCount()).isEqualTo(1L);
        assertThat(single.retweetCount()).isZero();
    }

    @Test
    void singleTweet_missingTweet_returnsNotFoundFailure() throws Exception {
        AuthResponse userA = registerUser("gete");

        ResponseEnvelope envelope = sendGetTweet(UUID.randomUUID(), userA.token());
        assertThat(envelope.isSuccess()).isFalse();
        assertThat(envelope.errorCode()).isEqualTo("TWEET_GET_FAILED");
        assertThat(envelope.errorMessage()).containsIgnoringCase("not found");
    }

    @Test
    void singleTweet_deletedTweet_notExposed() throws Exception {
        AuthResponse userA = registerUser("getf");
        AuthResponse userB = registerUser("getg");

        TweetResponse tweet = createTweet(userA, "single-tweet-3");
        deleteTweet(userA, tweet.id());

        ResponseEnvelope envelope = sendGetTweet(tweet.id(), userB.token());
        assertThat(envelope.isSuccess()).isFalse();
        assertThat(envelope.errorCode()).isEqualTo("TWEET_GET_FAILED");
        assertThat(envelope.errorMessage()).containsIgnoringCase("not found");
    }

    @Test
    void singleTweet_authorBlocksViewer_notExposedToViewer() throws Exception {
        AuthResponse userA = registerUser("geth");
        AuthResponse userB = registerUser("geti");

        TweetResponse tweet = createTweet(userA, "single-tweet-4");
        block(userA, userB.userId());

        // A viewer blocked by the author must not see the author's tweets.
        ResponseEnvelope blockedView = sendGetTweet(tweet.id(), userB.token());
        assertThat(blockedView.isSuccess()).isFalse();
        assertThat(blockedView.errorCode()).isEqualTo("TWEET_GET_FAILED");

        // The author's own view is unaffected by blocking a viewer.
        ResponseEnvelope ownView = sendGetTweet(tweet.id(), userA.token());
        assertSuccess(ownView, "author retrieves own tweet after blocking viewer");
    }

    @Test
    void singleTweet_viewerBlocksAuthor_notExposedToViewer() throws Exception {
        AuthResponse userA = registerUser("getj");
        AuthResponse userB = registerUser("getk");

        TweetResponse tweet = createTweet(userA, "single-tweet-5");
        block(userB, userA.userId());

        ResponseEnvelope envelope = sendGetTweet(tweet.id(), userB.token());
        assertThat(envelope.isSuccess()).isFalse();
        assertThat(envelope.errorCode()).isEqualTo("TWEET_GET_FAILED");
    }

    @Test
    void singleTweet_unauthenticated_rejectedWithUnauthorized() throws Exception {
        AuthResponse userA = registerUser("getl");
        TweetResponse tweet = createTweet(userA, "single-tweet-6");

        ResponseEnvelope envelope = sendUnauthorized(
                new RequestEnvelope(
                        UUID.randomUUID(),
                        RequestType.TWEET_GET,
                        gson.toJsonTree(new GetTweetRequest(tweet.id(), null)),
                        null));
        assertThat(envelope)
                .as("route must return a failure envelope, not null")
                .isNotNull();
        assertThat(envelope.isSuccess()).isFalse();
        assertThat(envelope.errorCode()).isEqualTo("AUTH_REQUIRED");
    }

    // ========================================================================
    // TWEET_UNRETWEET – un-repost
    // ========================================================================

    @Test
    void unretweet_repostedThenUnretweeted_succeedsAndRemovesMarker() throws Exception {
        AuthResponse userA = registerUser("urta");
        AuthResponse userB = registerUser("urtb");

        TweetResponse tweet = createTweet(userA, "unretweet-original-1");
        TweetResponse retweetRow = retweet(userB, tweet.id());
        assertThat(retweetRow.id()).isNotEqualTo(tweet.id());

        assertThat(countActiveRetweetRows(tweet.id(), userB.userId())).isEqualTo(1L);

        ResponseEnvelope envelope = sendUnretweet(tweet.id(), userB.token());
        assertSuccess(envelope, "B un-retweets tweet");

        // Response is the original tweet with refreshed derived counts.
        TweetResponse response = gson.fromJson(envelope.getData(), TweetResponse.class);
        assertThat(response.id()).isEqualTo(tweet.id());
        assertThat(response.content()).isEqualTo("unretweet-original-1");
        assertThat(response.retweetCount()).isZero();

        // Marker row is actually gone.
        assertThat(countActiveRetweetRows(tweet.id(), userB.userId())).isZero();
    }

    @Test
    void unretweet_retweetCountDecreasesByExactlyOne() throws Exception {
        AuthResponse userA = registerUser("urtc");
        AuthResponse userB = registerUser("urtd");

        TweetResponse tweet = createTweet(userA, "unretweet-original-2");
        retweet(userB, tweet.id());

        TimelineTweet before = readSingleTweet(tweet.id(), userA.token());
        assertThat(before.retweetCount()).isEqualTo(1L);

        assertSuccess(sendUnretweet(tweet.id(), userB.token()), "B un-retweets");

        TimelineTweet after = readSingleTweet(tweet.id(), userA.token());
        assertThat(after.retweetCount()).isEqualTo(0L);
    }

    @Test
    void unretweet_withoutActiveRetweet_fails() throws Exception {
        AuthResponse userA = registerUser("urte");
        AuthResponse userB = registerUser("urtf");

        TweetResponse tweet = createTweet(userA, "unretweet-original-3");

        ResponseEnvelope envelope = sendUnretweet(tweet.id(), userB.token());
        assertThat(envelope.isSuccess()).isFalse();
        assertThat(envelope.errorCode()).isEqualTo("TWEET_UNRETWEET_FAILED");
        assertThat(envelope.errorMessage()).containsIgnoringCase("not retweeted");
    }

    @Test
    void unretweet_originalTweetRemainsIntact() throws Exception {
        AuthResponse userA = registerUser("urtg");
        AuthResponse userB = registerUser("urth");

        TweetResponse tweet = createTweet(userA, "unretweet-original-4");
        retweet(userB, tweet.id());
        assertSuccess(sendUnretweet(tweet.id(), userB.token()), "B un-retweets");

        // Original is still active, unmodified, and readable.
        TimelineTweet original = readSingleTweet(tweet.id(), userB.token());
        assertThat(original.tweetId()).isEqualTo(tweet.id());
        assertThat(original.authorId()).isEqualTo(userA.userId());
        assertThat(original.content()).isEqualTo("unretweet-original-4");
    }

    @Test
    void unretweet_doesNotRemoveAnotherUsersRetweet() throws Exception {
        AuthResponse userA = registerUser("urti");
        AuthResponse userB = registerUser("urtj");
        AuthResponse userC = registerUser("urtk");

        TweetResponse tweet = createTweet(userA, "unretweet-original-5");
        retweet(userB, tweet.id());
        retweet(userC, tweet.id());

        assertSuccess(sendUnretweet(tweet.id(), userB.token()), "B un-retweets");

        // B's marker is gone; C's retweet remains untouched.
        assertThat(countActiveRetweetRows(tweet.id(), userB.userId())).isZero();
        assertThat(countActiveRetweetRows(tweet.id(), userC.userId())).isEqualTo(1L);

        TimelineTweet after = readSingleTweet(tweet.id(), userA.token());
        assertThat(after.retweetCount()).isEqualTo(1L);
    }

    @Test
    void unretweet_canRetweetAgainAfterwards() throws Exception {
        AuthResponse userA = registerUser("urtl");
        AuthResponse userB = registerUser("urtm");

        TweetResponse tweet = createTweet(userA, "unretweet-original-6");
        retweet(userB, tweet.id());
        assertSuccess(sendUnretweet(tweet.id(), userB.token()), "B un-retweets");

        // Duplicate-protection is re-enabled: B may repost again.
        retweet(userB, tweet.id());
        assertThat(countActiveRetweetRows(tweet.id(), userB.userId())).isEqualTo(1L);
    }

    @Test
    void unretweet_blockedUser_fails() throws Exception {
        AuthResponse userA = registerUser("urtn");
        AuthResponse userB = registerUser("urto");

        TweetResponse tweet = createTweet(userA, "unretweet-original-7");
        retweet(userB, tweet.id());
        block(userA, userB.userId());

        ResponseEnvelope envelope = sendUnretweet(tweet.id(), userB.token());
        assertThat(envelope.isSuccess()).isFalse();
        assertThat(envelope.errorCode()).isEqualTo("TWEET_UNRETWEET_FAILED");

        // Marker row is untouched when the operation is rejected.
        assertThat(countActiveRetweetRows(tweet.id(), userB.userId())).isEqualTo(1L);
    }

    @Test
    void unretweet_deletedOriginal_behavesConsistently() throws Exception {
        AuthResponse userA = registerUser("urtp");
        AuthResponse userB = registerUser("urtq");

        TweetResponse tweet = createTweet(userA, "unretweet-original-8");
        retweet(userB, tweet.id());
        deleteTweet(userA, tweet.id());

        // Mirrors retweet semantics: the original must be active; a deleted
        // original surfaces as not-found rather than exposing the tweet.
        ResponseEnvelope envelope = sendUnretweet(tweet.id(), userB.token());
        assertThat(envelope.isSuccess()).isFalse();
        assertThat(envelope.errorCode()).isEqualTo("TWEET_UNRETWEET_FAILED");
    }

    @Test
    void unretweet_unauthenticated_rejectedWithUnauthorized() throws Exception {
        AuthResponse userA = registerUser("urtr");
        TweetResponse tweet = createTweet(userA, "unretweet-original-9");

        ResponseEnvelope envelope = sendUnauthorized(
                new RequestEnvelope(
                        UUID.randomUUID(),
                        RequestType.TWEET_UNRETWEET,
                        gson.toJsonTree(new UnretweetRequest(tweet.id(), null)),
                        null));
        assertThat(envelope)
                .as("route must return a failure envelope, not null")
                .isNotNull();
        assertThat(envelope.isSuccess()).isFalse();
        assertThat(envelope.errorCode()).isEqualTo("AUTH_REQUIRED");
    }

    // ========================================================================
    // NOTIFICATION_* – notification foundation
    // ========================================================================

    @Test
    void notifications_like_createsNotificationForAuthor() throws Exception {
        AuthResponse author = registerUser("ntfa");
        AuthResponse liker = registerUser("ntfb");

        TweetResponse tweet = createTweet(author, "notif-like-1");
        like(liker, tweet.id());

        List<NotificationResponse> notifications = getNotifications(author.token());
        assertThat(notifications).hasSize(1);
        NotificationResponse n = notifications.get(0);
        assertThat(n.type()).isEqualTo(NotificationType.LIKE);
        assertThat(n.actor()).isNotNull();
        assertThat(n.actor().userId()).isEqualTo(liker.userId());
        assertThat(n.tweetId()).isEqualTo(tweet.id());
        assertThat(n.read()).isFalse();
        assertThat(n.createdAt()).isNotNull();
    }

    @Test
    void notifications_unlike_doesNotCreateAdditionalNotification() throws Exception {
        AuthResponse author = registerUser("ntfc");
        AuthResponse liker = registerUser("ntfd");

        TweetResponse tweet = createTweet(author, "notif-like-2");
        like(liker, tweet.id());
        unlike(liker, tweet.id());

        // The original like notification remains; unlike adds none.
        List<NotificationResponse> notifications = getNotifications(author.token());
        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0).type()).isEqualTo(NotificationType.LIKE);
    }

    @Test
    void notifications_reply_createsNotificationForParentAuthor() throws Exception {
        AuthResponse parentAuthor = registerUser("ntfe");
        AuthResponse replier = registerUser("ntff");

        TweetResponse parent = createTweet(parentAuthor, "notif-reply-1");
        reply(replier, parent.id(), "notif-reply-text");

        List<NotificationResponse> notifications = getNotifications(parentAuthor.token());
        assertThat(notifications).hasSize(1);
        NotificationResponse n = notifications.get(0);
        assertThat(n.type()).isEqualTo(NotificationType.REPLY);
        assertThat(n.actor().userId()).isEqualTo(replier.userId());
        assertThat(n.tweetId()).isEqualTo(parent.id());
    }

    @Test
    void notifications_retweet_createsNotificationForOriginalAuthor() throws Exception {
        AuthResponse author = registerUser("ntfg");
        AuthResponse retweeter = registerUser("ntfh");

        TweetResponse tweet = createTweet(author, "notif-retweet-1");
        retweet(retweeter, tweet.id());

        List<NotificationResponse> notifications = getNotifications(author.token());
        assertThat(notifications).hasSize(1);
        NotificationResponse n = notifications.get(0);
        assertThat(n.type()).isEqualTo(NotificationType.RETWEET);
        assertThat(n.actor().userId()).isEqualTo(retweeter.userId());
        assertThat(n.tweetId()).isEqualTo(tweet.id());
    }

    @Test
    void notifications_quote_createsNotificationForQuotedAuthor() throws Exception {
        AuthResponse author = registerUser("ntfi");
        AuthResponse quoter = registerUser("ntfj");

        TweetResponse quoted = createTweet(author, "notif-quote-1");
        quote(quoter, quoted.id(), "notif-quote-text");

        List<NotificationResponse> notifications = getNotifications(author.token());
        assertThat(notifications).hasSize(1);
        NotificationResponse n = notifications.get(0);
        assertThat(n.type()).isEqualTo(NotificationType.QUOTE);
        assertThat(n.actor().userId()).isEqualTo(quoter.userId());
        assertThat(n.tweetId()).isEqualTo(quoted.id());
    }

    @Test
    void notifications_follow_createsNotificationForFollowedUser() throws Exception {
        AuthResponse followed = registerUser("ntfk");
        AuthResponse follower = registerUser("ntfl");

        follow(follower, followed.userId());

        List<NotificationResponse> notifications = getNotifications(followed.token());
        assertThat(notifications).hasSize(1);
        NotificationResponse n = notifications.get(0);
        assertThat(n.type()).isEqualTo(NotificationType.FOLLOW);
        assertThat(n.actor().userId()).isEqualTo(follower.userId());
        assertThat(n.tweetId()).isNull();
    }

    @Test
    void notifications_selfInteraction_doesNotNotifySelf() throws Exception {
        AuthResponse user = registerUser("ntfm");

        TweetResponse tweet = createTweet(user, "notif-self-1");
        like(user, tweet.id());

        assertThat(getNotifications(user.token())).isEmpty();
        assertThat(countNotificationRows(user.userId())).isZero();
    }

    @Test
    void notifications_retrieval_newestFirst_withUnreadState() throws Exception {
        AuthResponse author = registerUser("ntfn");
        AuthResponse actorA = registerUser("ntfo");
        AuthResponse actorB = registerUser("ntfp");

        TweetResponse tweet = createTweet(author, "notif-order-1");
        like(actorA, tweet.id());
        reply(actorB, tweet.id(), "notif-order-reply");

        List<NotificationResponse> notifications = getNotifications(author.token());
        assertThat(notifications).hasSize(2);
        // All delivered unread.
        assertThat(notifications).allSatisfy(n -> assertThat(n.read()).isFalse());
        // Newest first (the reply happened after the like).
        assertThat(notifications.get(0).type()).isEqualTo(NotificationType.REPLY);
        assertThat(notifications.get(1).type()).isEqualTo(NotificationType.LIKE);
        // created_at is monotonically non-increasing.
        assertThat(notifications.get(0).createdAt())
                .isAfterOrEqualTo(notifications.get(1).createdAt());
    }

    @Test
    void notifications_read_marksSingleNotificationRead() throws Exception {
        AuthResponse author = registerUser("ntfq");
        AuthResponse actorA = registerUser("ntfr");
        AuthResponse actorB = registerUser("ntfs");

        TweetResponse tweet = createTweet(author, "notif-read-1");
        like(actorA, tweet.id());
        reply(actorB, tweet.id(), "notif-read-reply");

        List<NotificationResponse> before = getNotifications(author.token());
        assertThat(before).hasSize(2);

        NotificationResponse read = readNotification(author.token(), before.get(0).id());
        assertThat(read.id()).isEqualTo(before.get(0).id());
        assertThat(read.read()).isTrue();

        List<NotificationResponse> after = getNotifications(author.token());
        assertThat(after).filteredOn(n -> n.read())
                .extracting(NotificationResponse::id)
                .containsExactly(before.get(0).id());
        assertThat(after).filteredOn(n -> !n.read()).hasSize(1);
    }

    @Test
    void notifications_readAll_marksAllReadAndReturnsCount() throws Exception {
        AuthResponse author = registerUser("ntft");
        AuthResponse actorA = registerUser("ntfu");
        AuthResponse actorB = registerUser("ntfv");

        TweetResponse tweet = createTweet(author, "notif-readall-1");
        like(actorA, tweet.id());
        reply(actorB, tweet.id(), "notif-readall-reply");

        int updated = readAllNotifications(author.token());
        assertThat(updated).isEqualTo(2);

        List<NotificationResponse> after = getNotifications(author.token());
        assertThat(after).allSatisfy(n -> assertThat(n.read()).isTrue());
    }

    @Test
    void notifications_read_otherUsersNotification_forbidden() throws Exception {
        AuthResponse author = registerUser("ntfw");
        AuthResponse other = registerUser("ntfx");

        TweetResponse tweet = createTweet(author, "notif-owner-1");
        like(other, tweet.id());

        List<NotificationResponse> notifications = getNotifications(author.token());
        assertThat(notifications).hasSize(1);

        ResponseEnvelope envelope =
                sendReadNotification(other.token(), notifications.get(0).id());
        assertThat(envelope.isSuccess()).isFalse();
        assertThat(envelope.errorCode()).isEqualTo("NOTIFICATION_READ_FAILED");

        // The notification is still unread for its owner.
        List<NotificationResponse> after = getNotifications(author.token());
        assertThat(after.get(0).read()).isFalse();
    }

    @Test
    void notifications_blockedInteraction_doesNotProduceNotification() throws Exception {
        AuthResponse author = registerUser("ntfy");
        AuthResponse blocked = registerUser("ntfz");

        TweetResponse tweet = createTweet(author, "notif-block-1");
        block(author, blocked.userId());

        // The blocked like is rejected at interaction time...
        LikeTweetRequest likeRequest = new LikeTweetRequest(tweet.id(), blocked.token());
        ResponseEnvelope likeEnvelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_LIKE,
                gson.toJsonTree(likeRequest),
                null));
        assertThat(likeEnvelope.isSuccess()).isFalse();

        // ...and no notification row is created.
        assertThat(countNotificationRows(author.userId())).isZero();
    }

    @Test
    void notifications_unauthenticated_rejectedWithUnauthorized() throws Exception {
        ResponseEnvelope envelope = sendUnauthorized(
                new RequestEnvelope(
                        UUID.randomUUID(),
                        RequestType.NOTIFICATION_GET,
                        gson.toJsonTree(new GetNotificationsRequest(null)),
                        null));
        assertThat(envelope)
                .as("route must return a failure envelope, not null")
                .isNotNull();
        assertThat(envelope.isSuccess()).isFalse();
        assertThat(envelope.errorCode()).isEqualTo("AUTH_REQUIRED");
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private AuthResponse registerUser(String prefix) throws Exception {
        String username = prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest registerRequest = new RegisterRequest(
                username,
                username + "@repliestest.com",
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

    private TweetResponse createTweet(AuthResponse auth, String content) throws Exception {
        CreateTweetRequest createRequest = new CreateTweetRequest(content, null, null, null, auth.token(), null);
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

    private TweetResponse reply(AuthResponse author, UUID parentId, String text) throws Exception {
        ReplyTweetRequest replyRequest =
                new ReplyTweetRequest(parentId, text, null, author.token());
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

    private ResponseEnvelope sendReplies(UUID tweetId, String token) throws Exception {
        GetRepliesRequest repliesRequest = new GetRepliesRequest(tweetId, token);
        return send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_GET_REPLIES,
                gson.toJsonTree(repliesRequest),
                null));
    }

    private ResponseEnvelope sendGetTweet(UUID tweetId, String token) throws Exception {
        GetTweetRequest getTweetRequest = new GetTweetRequest(tweetId, token);
        return send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_GET,
                gson.toJsonTree(getTweetRequest),
                null));
    }

    private TweetResponse retweet(AuthResponse actor, UUID tweetId) throws Exception {
        RetweetRequest retweetRequest = new RetweetRequest(tweetId, actor.token());
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_RETWEET,
                gson.toJsonTree(retweetRequest),
                null));
        assertSuccess(envelope, "retweet tweet " + tweetId);

        TweetResponse retweetRow = gson.fromJson(envelope.getData(), TweetResponse.class);
        createdTweetIds.add(retweetRow.id());
        return retweetRow;
    }

    private ResponseEnvelope sendUnretweet(UUID tweetId, String token) throws Exception {
        UnretweetRequest unretweetRequest = new UnretweetRequest(tweetId, token);
        return send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_UNRETWEET,
                gson.toJsonTree(unretweetRequest),
                null));
    }

    private TweetResponse quote(AuthResponse author, UUID quotedTweetId, String content) throws Exception {
        CreateTweetRequest createRequest =
                new CreateTweetRequest(content, null, quotedTweetId, null, author.token(), null);
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_CREATE,
                gson.toJsonTree(createRequest),
                null));
        assertSuccess(envelope, "quote tweet " + quotedTweetId);

        TweetResponse tweet = gson.fromJson(envelope.getData(), TweetResponse.class);
        createdTweetIds.add(tweet.id());
        return tweet;
    }

    private void follow(AuthResponse follower, UUID followingId) throws Exception {
        FollowUserRequest followRequest = new FollowUserRequest(followingId, follower.token());
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.RELATION_FOLLOW,
                gson.toJsonTree(followRequest),
                null));
        assertSuccess(envelope, "follow user " + followingId);
    }

    private void unlike(AuthResponse liker, UUID tweetId) throws Exception {
        UnlikeTweetRequest unlikeRequest = new UnlikeTweetRequest(tweetId, liker.token());
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_UNLIKE,
                gson.toJsonTree(unlikeRequest),
                null));
        assertSuccess(envelope, "unlike tweet " + tweetId);
    }

    private List<NotificationResponse> getNotifications(String token) throws Exception {
        GetNotificationsRequest request = new GetNotificationsRequest(token);
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.NOTIFICATION_GET,
                gson.toJsonTree(request),
                null));
        assertSuccess(envelope, "GET notifications");

        return gson.fromJson(
                envelope.getData(),
                TypeToken.getParameterized(List.class, NotificationResponse.class).getType());
    }

    private NotificationResponse readNotification(String token, UUID notificationId) throws Exception {
        ReadNotificationRequest request = new ReadNotificationRequest(notificationId, token);
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.NOTIFICATION_READ,
                gson.toJsonTree(request),
                null));
        assertSuccess(envelope, "READ notification " + notificationId);

        return gson.fromJson(envelope.getData(), NotificationResponse.class);
    }

    private ResponseEnvelope sendReadNotification(String token, UUID notificationId) throws Exception {
        ReadNotificationRequest request = new ReadNotificationRequest(notificationId, token);
        return send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.NOTIFICATION_READ,
                gson.toJsonTree(request),
                null));
    }

    private int readAllNotifications(String token) throws Exception {
        ReadAllNotificationsRequest request = new ReadAllNotificationsRequest(token);
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.NOTIFICATION_READ_ALL,
                gson.toJsonTree(request),
                null));
        assertSuccess(envelope, "READ_ALL notifications");

        Integer updated = gson.fromJson(envelope.getData(), Integer.class);
        return updated == null ? 0 : updated;
    }

    private long countNotificationRows(UUID recipientId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE recipient_id = ?",
                Long.class,
                recipientId);
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

    private TimelineTweet readSingleTweet(UUID tweetId, String token) throws Exception {
        ResponseEnvelope envelope = sendGetTweet(tweetId, token);
        assertSuccess(envelope, "GET tweet " + tweetId);
        return gson.fromJson(envelope.getData(), TimelineTweet.class);
    }

    private void like(AuthResponse liker, UUID tweetId) throws Exception {
        LikeTweetRequest likeRequest = new LikeTweetRequest(tweetId, liker.token());
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_LIKE,
                gson.toJsonTree(likeRequest),
                null));
        assertSuccess(envelope, "like tweet " + tweetId);
    }

    private void deleteTweet(AuthResponse author, UUID tweetId) throws Exception {
        DeleteTweetRequest deleteRequest = new DeleteTweetRequest(tweetId, author.token());
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_DELETE,
                gson.toJsonTree(deleteRequest),
                null));
        assertSuccess(envelope, "delete tweet " + tweetId);
    }

    private void block(AuthResponse blocker, UUID blockedUserId) throws Exception {
        BlockUserRequest blockRequest = new BlockUserRequest(blockedUserId, blocker.token());
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.RELATION_BLOCK,
                gson.toJsonTree(blockRequest),
                null));
        assertSuccess(envelope, "block user " + blockedUserId);
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
