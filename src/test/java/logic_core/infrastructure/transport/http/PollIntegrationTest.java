package logic_core.infrastructure.transport.http;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import logic_core.app.dto.request.BookmarkTweetRequest;
import logic_core.app.dto.request.CreateTweetRequest;
import logic_core.app.dto.request.DeleteTweetRequest;
import logic_core.app.dto.request.GetBookmarksRequest;
import logic_core.app.dto.request.GetHashtagTweetsRequest;
import logic_core.app.dto.request.GetRepliesRequest;
import logic_core.app.dto.request.GetTimelineRequest;
import logic_core.app.dto.request.GetTimelineResponse;
import logic_core.app.dto.request.GetTweetRequest;
import logic_core.app.dto.request.PollRequest;
import logic_core.app.dto.request.RegisterRequest;
import logic_core.app.dto.request.VotePollRequest;
import logic_core.app.dto.response.AuthResponse;
import logic_core.app.dto.response.GetBookmarksResponse;
import logic_core.app.dto.response.HashtagTweetsResponse;
import logic_core.app.dto.response.PollOptionResponse;
import logic_core.app.dto.response.PollResponse;
import logic_core.app.dto.response.TweetResponse;
import logic_core.app.dto.timeline.TimelineTweet;
import logic_core.domain.repository.TimelineType;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.net.ServerSocket;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Core integration suite — poll system.
 *
 * <p>Drives the full stack ({@code POST /api} → {@code RequestDispatcher} →
 * Facade → UseCase → Repository Adapter → JPA → PostgreSQL) for the V2.1 poll
 * feature: poll creation through {@code TWEET_CREATE}, poll surfacing in the
 * tweet response, single-vote casting, duplicate-vote prevention, expiry,
 * block barrier, and the poll-deletion cascade.
 *
 * <p>Uses the same real-PostgreSQL harness as the other HTTP integration
 * tests: the Spring context boots against {@code xclonedb} (Flyway applies
 * the V1 baseline with the poll tables, Hibernate {@code ddl-auto=validate}
 * passes) and every row created here is removed in
 * {@link #cleanUpCreatedRows()}.
 */
@SpringBootTest(classes = ServerMain.class)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PollIntegrationTest
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

                jdbcTemplate.update(
                        "DELETE FROM poll_votes WHERE poll_id IN ("
                                + "SELECT p.id FROM polls p WHERE p.tweet_id IN (" + placeholders + "))",
                        tweetArgs);
                jdbcTemplate.update(
                        "DELETE FROM poll_options WHERE poll_id IN ("
                                + "SELECT p.id FROM polls p WHERE p.tweet_id IN (" + placeholders + "))",
                        tweetArgs);
                jdbcTemplate.update(
                        "DELETE FROM polls WHERE tweet_id IN (" + placeholders + ")",
                        tweetArgs);
                jdbcTemplate.update(
                        "DELETE FROM bookmarks WHERE tweet_id IN (" + placeholders + ")",
                        tweetArgs);
                jdbcTemplate.update(
                        "DELETE FROM tweet_mentions WHERE tweet_id IN (" + placeholders + ")",
                        tweetArgs);
                jdbcTemplate.update(
                        "DELETE FROM likes WHERE tweet_id IN (" + placeholders + ")",
                        tweetArgs);
                jdbcTemplate.update(
                        "DELETE FROM hashtags WHERE id IN ("
                                + " SELECT hashtag_id FROM tweet_hashtags WHERE tweet_id IN ("
                                + placeholders + ")"
                                + ")",
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
                            "DELETE FROM poll_votes WHERE user_id IN (" + placeholders + ")",
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
                    // Best-effort only.
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
            System.err.println("PollIntegrationTest cleanup warning: " + e.getMessage());
        }
    }

    // ========================================================================
    // Creation + read model
    // ========================================================================

    @Test
    void createTweet_withoutPoll_returnsNullPoll() throws Exception
    {
        AuthResponse author = registerUser("pol");

        TweetResponse tweet = createTweet(author, "no-poll-1", null);
        assertThat(tweet.poll()).isNull();
    }

    @Test
    void createTweet_withValidPoll_persistsAndReturnsPoll() throws Exception
    {
        AuthResponse author = registerUser("pol");

        PollRequest pollRequest = new PollRequest(
                "Favorite language?",
                List.of("Java", "Kotlin", "Go"),
                60
        );

        TweetResponse tweet = createTweet(author, "poll-1", pollRequest);

        assertThat(tweet.poll()).isNotNull();
        assertThat(tweet.poll().question()).isEqualTo("Favorite language?");
        assertThat(tweet.poll().options()).hasSize(3);
        assertThat(tweet.poll().options().get(0).text()).isEqualTo("Java");
        assertThat(tweet.poll().totalVotes()).isZero();
        assertThat(tweet.poll().expired()).isFalse();

        assertThat(countPollRowsForTweet(tweet.id())).isEqualTo(1L);
        assertThat(countPollOptionRowsForTweet(tweet.id())).isEqualTo(3L);
    }

    @Test
    void createTweet_withInvalidPoll_failsAndTweetIsNotCreated() throws Exception
    {
        AuthResponse author = registerUser("pol");

        // Only one option is invalid (2+ required).
        PollRequest invalidPoll = new PollRequest("Question?", List.of("OnlyOne"), 60);

        ResponseEnvelope response = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_CREATE,
                gson.toJsonTree(new CreateTweetRequest(
                        "invalid-poll-tweet",
                        null,
                        null,
                        null,
                        author.token(),
                        null,
                        invalidPoll)),
                null));
        assertThat(response.isSuccess()).isFalse();
        assertThat(response.errorCode()).isEqualTo("TWEET_CREATE_FAILED");
        assertThat(response.errorMessage()).containsIgnoringCase("option");
    }

    @Test
    void createTweet_withPollAndEmptyContent_isAllowed() throws Exception
    {
        AuthResponse author = registerUser("pol");

        PollRequest pollRequest = new PollRequest("Empty content?", List.of("Yes", "No"), 60);

        TweetResponse tweet = createTweet(author, "", pollRequest);
        assertThat(tweet.poll()).isNotNull();
        assertThat(tweet.poll().options()).hasSize(2);
    }

    // ========================================================================
    // Voting
    // ========================================================================

    @Test
    void vote_successful_updatesCountsAndResults() throws Exception
    {
        AuthResponse author = registerUser("pol");
        AuthResponse voter = registerUser("pol");

        PollRequest pollRequest = new PollRequest("Vote me?", List.of("A", "B"), 60);
        TweetResponse tweet = createTweet(author, "vote-1", pollRequest);

        UUID pollId = tweet.poll().pollId();
        UUID optionA = tweet.poll().options().get(0).optionId();

        PollResponse result = vote(voter, pollId, optionA);
        assertThat(result.totalVotes()).isEqualTo(1);
        assertThat(result.options().get(0).voteCount()).isEqualTo(1);
    }

    @Test
    void duplicateVote_isRejected() throws Exception
    {
        AuthResponse author = registerUser("pol");
        AuthResponse voter = registerUser("pol");

        PollRequest pollRequest = new PollRequest("One vote", List.of("A", "B"), 60);
        TweetResponse tweet = createTweet(author, "vote-2", pollRequest);

        UUID pollId = tweet.poll().pollId();
        UUID optionA = tweet.poll().options().get(0).optionId();

        vote(voter, pollId, optionA);

        ResponseEnvelope response = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.POLL_VOTE,
                gson.toJsonTree(new VotePollRequest(pollId, optionA, voter.token())),
                null));
        assertThat(response.isSuccess()).isFalse();
        assertThat(response.errorCode()).isEqualTo("POLL_VOTE_FAILED");
        assertThat(response.errorMessage()).containsIgnoringCase("already voted");

        assertThat(countVoteRowsForPoll(pollId)).isEqualTo(1L);
    }

    @Test
    void vote_forNonexistentPoll_fails() throws Exception
    {
        AuthResponse voter = registerUser("pol");

        ResponseEnvelope response = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.POLL_VOTE,
                gson.toJsonTree(new VotePollRequest(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        voter.token())),
                null));
        assertThat(response.isSuccess()).isFalse();
        assertThat(response.errorCode()).isEqualTo("POLL_VOTE_FAILED");
    }

    @Test
    void vote_forOptionNotInPoll_fails() throws Exception
    {
        AuthResponse author = registerUser("pol");
        AuthResponse voter = registerUser("pol");

        PollRequest pollRequest = new PollRequest("Wrong option", List.of("A", "B"), 60);
        TweetResponse tweet = createTweet(author, "vote-3", pollRequest);

        ResponseEnvelope response = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.POLL_VOTE,
                gson.toJsonTree(new VotePollRequest(
                        tweet.poll().pollId(),
                        UUID.randomUUID(),
                        voter.token())),
                null));
        assertThat(response.isSuccess()).isFalse();
        assertThat(response.errorCode()).isEqualTo("POLL_VOTE_FAILED");
    }

    @Test
    void multipleUsers_canVoteOnSamePoll() throws Exception
    {
        AuthResponse author = registerUser("pol");
        AuthResponse voterA = registerUser("pol");
        AuthResponse voterB = registerUser("pol");
        AuthResponse voterC = registerUser("pol");

        PollRequest pollRequest = new PollRequest("Multi", List.of("X", "Y"), 60);
        TweetResponse tweet = createTweet(author, "vote-4", pollRequest);

        UUID pollId = tweet.poll().pollId();
        UUID optionX = tweet.poll().options().get(0).optionId();
        UUID optionY = tweet.poll().options().get(1).optionId();

        vote(voterA, pollId, optionX);
        vote(voterB, pollId, optionX);
        vote(voterC, pollId, optionY);

        assertThat(countVoteRowsForPoll(pollId)).isEqualTo(3L);
    }

    @Test
    void vote_afterExpiry_isRejected_andResponseFlagsExpired() throws Exception
    {
        AuthResponse author = registerUser("pol");
        AuthResponse voter = registerUser("pol");

        PollRequest pollRequest = new PollRequest("Expiring?", List.of("A", "B"), 60);
        TweetResponse tweet = createTweet(author, "expiry-poll-1", pollRequest);

        UUID pollId = tweet.poll().pollId();
        UUID optionA = tweet.poll().options().get(0).optionId();

        // Pin the poll into the past so expiry is deterministic regardless of
        // when the test runs (same JDBC timestamp pattern used elsewhere).
        jdbcTemplate.update(
                "UPDATE polls SET expires_at = ? WHERE id = ?",
                OffsetDateTime.now().minusMinutes(1),
                pollId);

        // Voting after expiry is rejected with the existing error contract.
        ResponseEnvelope response = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.POLL_VOTE,
                gson.toJsonTree(new VotePollRequest(pollId, optionA, voter.token())),
                null));
        assertThat(response.isSuccess()).isFalse();
        assertThat(response.errorCode()).isEqualTo("POLL_VOTE_FAILED");
        assertThat(response.errorMessage()).containsIgnoringCase("expired");

        // No vote row was persisted for the rejected vote.
        assertThat(countVoteRowsForPoll(pollId)).isZero();

        // Reads expose the expired state on the poll.
        TimelineTweet single = getSingleTweet(author.token(), tweet.id());
        assertThat(single.poll()).isNotNull();
        assertThat(single.poll().pollId()).isEqualTo(pollId);
        assertThat(single.poll().expired()).isTrue();
    }

    // ========================================================================
    // Deletion cascade
    // ========================================================================

    @Test
    void deletingTweet_removesPollData() throws Exception
    {
        AuthResponse author = registerUser("pol");
        AuthResponse voter = registerUser("pol");

        PollRequest pollRequest = new PollRequest("Delete me", List.of("A", "B"), 60);
        TweetResponse tweet = createTweet(author, "delete-poll-1", pollRequest);

        UUID pollId = tweet.poll().pollId();
        vote(voter, pollId, tweet.poll().options().get(0).optionId());

        assertThat(countPollRowsForTweet(tweet.id())).isEqualTo(1L);

        deleteTweet(tweet.id(), author.token());

        assertThat(countPollRowsForTweet(tweet.id())).isZero();
    }

    // ========================================================================
    // TimelineTweet read model
    // ========================================================================

    @Test
    void timelineTweet_withoutPoll_hasNullPoll() throws Exception
    {
        AuthResponse author = registerUser("pol");

        TweetResponse plain = createTweet(author, "plain-no-poll", null);

        GetTimelineResponse timeline = getHomeTimeline(author);
        assertThat(timeline.tweets()).isNotEmpty();

        TimelineTweet found = timeline.tweets().stream()
                .filter(t -> t.tweetId().equals(plain.id()))
                .findFirst()
                .orElseThrow();
        assertThat(found.poll()).isNull();
    }

    @Test
    void timelineTweet_withPoll_includesPollOptionsAndCounts() throws Exception
    {
        AuthResponse author = registerUser("pol");
        AuthResponse voter = registerUser("pol");

        PollRequest pollRequest = new PollRequest(
                "Timeline question?",
                List.of("Yes", "No"),
                60);
        TweetResponse tweet = createTweet(author, "timeline-poll-1", pollRequest);

        // Vote so counts are non-zero before reading the timeline.
        UUID pollId = tweet.poll().pollId();
        UUID optionYes = tweet.poll().options().get(0).optionId();
        vote(voter, pollId, optionYes);

        GetTimelineResponse timeline = getHomeTimeline(author);
        TimelineTweet found = timeline.tweets().stream()
                .filter(t -> t.tweetId().equals(tweet.id()))
                .findFirst()
                .orElseThrow();

        assertThat(found.poll()).isNotNull();
        assertThat(found.poll().pollId()).isEqualTo(pollId);
        assertThat(found.poll().question()).isEqualTo("Timeline question?");
        assertThat(found.poll().totalVotes()).isEqualTo(1);
        assertThat(found.poll().options()).hasSize(2);
        assertThat(found.poll().options().get(0).text()).isEqualTo("Yes");
        assertThat(found.poll().options().get(0).voteCount()).isEqualTo(1);
        assertThat(found.poll().expired()).isFalse();
    }

    @Test
    void singleTweetRead_includesPoll() throws Exception
    {
        AuthResponse author = registerUser("pol");

        PollRequest pollRequest = new PollRequest("Single?", List.of("A", "B"), 60);
        TweetResponse tweet = createTweet(author, "single-poll-1", pollRequest);

        TimelineTweet single = getSingleTweet(author.token(), tweet.id());
        assertThat(single.poll()).isNotNull();
        assertThat(single.poll().pollId()).isEqualTo(tweet.poll().pollId());
        assertThat(single.poll().options()).hasSize(2);
    }

    @Test
    void repliesFeed_exposesPoll_whenReplyCarriesPoll() throws Exception
    {
        AuthResponse author = registerUser("pol");

        TweetResponse parent = createTweet(author, "parent-for-poll-reply", null);

        // A poll-bearing tweet that is itself a reply to the parent.
        PollRequest pollRequest = new PollRequest("Reply poll?", List.of("X", "Y"), 60);
        TweetResponse pollReply = createReplyWithPoll(
                author,
                parent.id(),
                "reply carries a poll",
                pollRequest);

        List<TimelineTweet> replies = getReplies(author.token(), parent.id());
        TimelineTweet found = replies.stream()
                .filter(t -> t.tweetId().equals(pollReply.id()))
                .findFirst()
                .orElseThrow();
        assertThat(found.poll()).isNotNull();
        assertThat(found.poll().pollId()).isEqualTo(pollReply.poll().pollId());
        assertThat(found.poll().options()).hasSize(2);
    }

    @Test
    void bookmarkFeed_exposesPoll() throws Exception
    {
        AuthResponse author = registerUser("pol");
        AuthResponse reader = registerUser("pol");

        PollRequest pollRequest = new PollRequest("Bookmark poll?", List.of("1", "2"), 60);
        TweetResponse tweet = createTweet(author, "bookmark-poll-1", pollRequest);

        bookmark(reader, tweet.id());

        GetBookmarksResponse bookmarks = getBookmarks(reader.token());
        TimelineTweet found = bookmarks.tweets().stream()
                .filter(t -> t.tweetId().equals(tweet.id()))
                .findFirst()
                .orElseThrow();
        assertThat(found.poll()).isNotNull();
        assertThat(found.poll().pollId()).isEqualTo(tweet.poll().pollId());
    }

    @Test
    void hashtagFeed_exposesPoll() throws Exception
    {
        AuthResponse author = registerUser("pol");

        PollRequest pollRequest = new PollRequest("Hashtag poll?", List.of("A", "B"), 60);
        TweetResponse tweet = createTweet(author, "reading #readpolltag data", pollRequest);

        HashtagTweetsResponse feed = getHashtagTweets("readpolltag", author.token());
        TimelineTweet found = feed.tweets().stream()
                .filter(t -> t.tweetId().equals(tweet.id()))
                .findFirst()
                .orElseThrow();
        assertThat(found.poll()).isNotNull();
        assertThat(found.poll().pollId()).isEqualTo(tweet.poll().pollId());
    }

    // ========================================================================
    // Auth
    // ========================================================================

    @Test
    void vote_requiresAuthentication() throws Exception
    {
        org.springframework.mock.web.MockHttpServletResponse response =
                mockMvc.perform(post("/api")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(gson.toJson(new RequestEnvelope(
                                        UUID.randomUUID(),
                                        RequestType.POLL_VOTE,
                                        gson.toJsonTree(new VotePollRequest(
                                                UUID.randomUUID(),
                                                UUID.randomUUID(),
                                                null)),
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
                username + "@polltest.com",
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
            PollRequest poll) throws Exception
    {
        CreateTweetRequest createRequest = new CreateTweetRequest(
                content, null, null, null, author.token(), null, poll);
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

    private TweetResponse createReplyWithPoll(
            AuthResponse author,
            UUID parentTweetId,
            String content,
            PollRequest poll) throws Exception
    {
        CreateTweetRequest createRequest = new CreateTweetRequest(
                content,
                parentTweetId,
                null,
                null,
                author.token(),
                null,
                poll);
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_CREATE,
                gson.toJsonTree(createRequest),
                null));
        assertSuccess(envelope, "create poll reply " + content);

        TweetResponse tweet = gson.fromJson(envelope.getData(), TweetResponse.class);
        createdTweetIds.add(tweet.id());
        return tweet;
    }

    private PollResponse vote(AuthResponse voter, UUID pollId, UUID optionId)
            throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.POLL_VOTE,
                gson.toJsonTree(new VotePollRequest(pollId, optionId, voter.token())),
                null));
        assertSuccess(envelope, "vote on " + pollId);
        return gson.fromJson(envelope.getData(), PollResponse.class);
    }

    private GetTimelineResponse getHomeTimeline(AuthResponse user) throws Exception
    {
        GetTimelineRequest request = new GetTimelineRequest(
                TimelineType.HOME,
                user.userId(),
                null,
                0,
                20,
                user.token());
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TIMELINE_GET,
                gson.toJsonTree(request),
                null));
        assertSuccess(envelope, "home timeline");
        return gson.fromJson(envelope.getData(), GetTimelineResponse.class);
    }

    private TimelineTweet getSingleTweet(String token, UUID tweetId) throws Exception
    {
        GetTweetRequest request = new GetTweetRequest(tweetId, token);
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_GET,
                gson.toJsonTree(request),
                null));
        assertSuccess(envelope, "get tweet " + tweetId);
        return gson.fromJson(envelope.getData(), TimelineTweet.class);
    }

    private List<TimelineTweet> getReplies(String token, UUID tweetId) throws Exception
    {
        GetRepliesRequest request = new GetRepliesRequest(tweetId, token);
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_GET_REPLIES,
                gson.toJsonTree(request),
                null));
        assertSuccess(envelope, "get replies of " + tweetId);
        return gson.fromJson(
                envelope.getData(),
                TypeToken.getParameterized(List.class, TimelineTweet.class).getType());
    }

    private void bookmark(AuthResponse reader, UUID tweetId) throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_BOOKMARK,
                gson.toJsonTree(new BookmarkTweetRequest(tweetId, reader.token())),
                null));
        assertSuccess(envelope, "bookmark " + tweetId);
    }

    private GetBookmarksResponse getBookmarks(String token) throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.BOOKMARKS_GET,
                gson.toJsonTree(new GetBookmarksRequest(0, 20, token)),
                null));
        assertSuccess(envelope, "get bookmarks");
        return gson.fromJson(envelope.getData(), GetBookmarksResponse.class);
    }

    private HashtagTweetsResponse getHashtagTweets(String tag, String token)
            throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.HASHTAG_GET_TWEETS,
                gson.toJsonTree(new GetHashtagTweetsRequest(tag, 0, 20, token)),
                null));
        assertSuccess(envelope, "hashtag feed " + tag);
        return gson.fromJson(envelope.getData(), HashtagTweetsResponse.class);
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

    private long countPollRowsForTweet(UUID tweetId)
    {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM polls WHERE tweet_id = ?",
                Long.class,
                tweetId);
        return count == null ? 0L : count;
    }

    private long countPollOptionRowsForTweet(UUID tweetId)
    {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM poll_options o JOIN polls p ON p.id = o.poll_id "
                        + "WHERE p.tweet_id = ?",
                Long.class,
                tweetId);
        return count == null ? 0L : count;
    }

    private long countVoteRowsForPoll(UUID pollId)
    {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM poll_votes WHERE poll_id = ?",
                Long.class,
                pollId);
        return count == null ? 0L : count;
    }

    private ResponseEnvelope send(RequestEnvelope request) throws Exception
    {
        org.springframework.mock.web.MockHttpServletResponse response =
                mockMvc.perform(post("/api")
                                .contentType(MediaType.APPLICATION_JSON)
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
                .as("%s — errorCode=%s, errorMessage=%s",
                        step, envelope.errorCode(), envelope.errorMessage())
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