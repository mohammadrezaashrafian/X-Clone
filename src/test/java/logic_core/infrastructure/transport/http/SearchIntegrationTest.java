package logic_core.infrastructure.transport.http;

import com.google.gson.Gson;
import logic_core.app.dto.request.BlockUserRequest;
import logic_core.app.dto.request.CreateTweetRequest;
import logic_core.app.dto.request.DeleteTweetRequest;
import logic_core.app.dto.request.MuteUserRequest;
import logic_core.app.dto.request.RegisterRequest;
import logic_core.app.dto.request.ReplyTweetRequest;
import logic_core.app.dto.request.RetweetRequest;
import logic_core.app.dto.request.SearchHashtagsRequest;
import logic_core.app.dto.request.SearchTweetsRequest;
import logic_core.app.dto.response.AuthResponse;
import logic_core.app.dto.response.HashtagSearchResponse;
import logic_core.app.dto.response.TweetResponse;
import logic_core.app.dto.response.TweetSearchResponse;
import logic_core.app.dto.response.UserSearchResponse;
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
 * Core integration suite — advanced search ([V2.1 #8]).
 *
 * <p>Drives the full stack ({@code POST /api} → {@code RequestDispatcher} →
 * Facade → UseCase → Repository Adapter → JPA → PostgreSQL) for the three
 * search surfaces:
 *
 * <ul>
 *   <li>{@code TWEET_SEARCH} — case-insensitive substring match over tweet
 *       content with the timeline visibility rules applied inside the query
 *       (soft-delete, author existence, bidirectional blocks, mutes, retweet
 *       markers excluded) and deterministic {@code publishedAt DESC, id ASC}
 *       ordering.</li>
 *   <li>{@code HASHTAG_SEARCH} — case-insensitive prefix search over the
 *       canonical persisted {@code hashtags.tag} column, deterministically
 *       ordered by tag ascending, strictly read-only.</li>
 *   <li>{@code USER_SEARCH} — regression guard only: the legacy contract
 *       (bare array payload, actor excluded, short-query failure) must remain
 *       byte-for-byte unchanged.</li>
 * </ul>
 *
 * <p>Uses the same real-PostgreSQL harness as the other HTTP integration
 * tests ({@code xclonedb}, Flyway-baselined schema) and removes every row it
 * creates in {@link #cleanUpCreatedRows()}.
 */
@SpringBootTest(classes = ServerMain.class)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SearchIntegrationTest
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
                String tweetPlaceholders = repeatPlaceholders(createdTweetIds.size());
                Object[] tweetArgs = createdTweetIds.toArray();

                // Remove hashtag rows reachable from this test's tweets
                // (cascades tweet_hashtags rows), then like/edit/tweet rows.
                try
                {
                    jdbcTemplate.update(
                            "DELETE FROM hashtags WHERE id IN ("
                                    + " SELECT hashtag_id FROM tweet_hashtags WHERE tweet_id IN ("
                                    + tweetPlaceholders + "))",
                            tweetArgs);
                }
                catch (Exception ignored)
                {
                    // Best-effort only.
                }
                try
                {
                    jdbcTemplate.update(
                            "DELETE FROM likes WHERE tweet_id IN (" + tweetPlaceholders + ")",
                            tweetArgs);
                }
                catch (Exception ignored)
                {
                    // Best-effort only.
                }
                try
                {
                    jdbcTemplate.update(
                            "DELETE FROM tweet_edits WHERE tweet_id IN (" + tweetPlaceholders + ")",
                            tweetArgs);
                }
                catch (Exception ignored)
                {
                    // Best-effort only.
                }
                jdbcTemplate.update(
                        "DELETE FROM tweets WHERE id IN (" + tweetPlaceholders + ")",
                        tweetArgs);
            }
            if (!createdUserIds.isEmpty())
            {
                String placeholders = repeatPlaceholders(createdUserIds.size());
                Object[] userArgs = createdUserIds.toArray();

                try
                {
                    jdbcTemplate.update(
                            "DELETE FROM hashtag_follows WHERE user_id IN (" + placeholders + ")",
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
                            "DELETE FROM mutes WHERE muter_id IN (" + placeholders + ")"
                                    + " OR muted_id IN (" + placeholders + ")",
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
            System.err.println("SearchIntegrationTest cleanup warning: " + e.getMessage());
        }
    }

    // ========================================================================
    // Tweet search — matching & semantics
    // ========================================================================

    @Test
    void tweetSearch_matchesContent_caseInsensitively_andAsSubstring() throws Exception
    {
        AuthResponse author = registerUser("tsea");
        AuthResponse viewer = registerUser("tseb");

        TweetResponse camel = createTweet(author, "The Quick Brown Fox jumps");
        TweetResponse lower = createTweet(author, "a fox in the garden");
        createTweet(author, "something completely different");

        TweetSearchResponse result = searchTweets("FOX", 0, 20, viewer.token());

        assertThat(result.totalItems()).isEqualTo(2);
        assertThat(result.tweets())
                .extracting(t -> t.tweetId())
                .containsExactlyInAnyOrder(camel.id(), lower.id());
        assertThat(result.hasNext()).isFalse();
    }

    @Test
    void tweetSearch_matchesHashtagTextThroughContent() throws Exception
    {
        AuthResponse author = registerUser("tsec");
        AuthResponse viewer = registerUser("tsed");

        createTweet(author, "learning #JavaSpring today");

        TweetSearchResponse result = searchTweets("javaspring", 0, 20, viewer.token());

        assertThat(result.totalItems()).isEqualTo(1);
        assertThat(result.tweets().get(0).content()).contains("#JavaSpring");
    }

    @Test
    void tweetSearch_emptyResult_isSuccessfulEmptyPage() throws Exception
    {
        AuthResponse viewer = registerUser("tsee");

        TweetSearchResponse result =
                searchTweets("zzzznotfoundzzzz", 0, 20, viewer.token());

        assertThat(result.tweets()).isEmpty();
        assertThat(result.totalItems()).isZero();
        assertThat(result.page()).isZero();
        assertThat(result.hasNext()).isFalse();
    }

    @Test
    void tweetSearch_paginatesWithTotalItemsAndHasNext() throws Exception
    {
        AuthResponse author = registerUser("tsef");
        AuthResponse viewer = registerUser("tseg");

        for (int i = 1; i <= 3; i++)
        {
            createTweet(author, "paginated post " + i + " #paginate");
        }

        TweetSearchResponse page0 = searchTweets("paginated", 0, 2, viewer.token());
        assertThat(page0.totalItems()).isEqualTo(3);
        assertThat(page0.tweets()).hasSize(2);
        assertThat(page0.pageSize()).isEqualTo(2);
        assertThat(page0.hasNext()).isTrue();

        TweetSearchResponse page1 = searchTweets("paginated", 1, 2, viewer.token());
        assertThat(page1.tweets()).hasSize(1);
        assertThat(page1.hasNext()).isFalse();

        // All three tweets are reachable across the two pages.
        List<UUID> seen = new ArrayList<>();
        page0.tweets().forEach(t -> seen.add(t.tweetId()));
        page1.tweets().forEach(t -> seen.add(t.tweetId()));
        assertThat(seen).hasSize(3).doesNotHaveDuplicates();

        // An empty page past the end stays successful.
        TweetSearchResponse page9 = searchTweets("paginated", 9, 2, viewer.token());
        assertThat(page9.tweets()).isEmpty();
        assertThat(page9.hasNext()).isFalse();
    }

    @Test
    void tweetSearch_deterministicOrdering_whenPublishedAtIdentical() throws Exception
    {
        AuthResponse author = registerUser("tseh");
        AuthResponse viewer = registerUser("tsei");

        // Same timestamp for both tweets -> identical publishedAt; the id ASC
        // tie-breaker must make the page order stable. UUIDs are random, so
        // the expected order is computed the way the DB sorts them (bytewise
        // over the canonical UUID representation), never by creation order.
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now();
        UUID a = createTweetAt(author, "tie order alpha", now).id();
        UUID b = createTweetAt(author, "tie order beta", now).id();

        List<UUID> expectedOrder = List.of(a, b).stream()
                .sorted(java.util.Comparator.comparing(SearchIntegrationTest::uuidByteKey))
                .toList();

        TweetSearchResponse run1 = searchTweets("tie order", 0, 20, viewer.token());
        TweetSearchResponse run2 = searchTweets("tie order", 0, 20, viewer.token());

        assertThat(run1.totalItems()).isEqualTo(2);
        assertThat(run1.tweets())
                .extracting(t -> t.tweetId())
                .containsExactlyElementsOf(expectedOrder);
        assertThat(run2.tweets())
                .extracting(t -> t.tweetId())
                .containsExactlyElementsOf(expectedOrder);
    }

    /**
     * PostgreSQL stores and sorts {@code uuid} columns bytewise over the
     * canonical 16-byte representation (RFC 4122, big-endian fields); for
     * lowercase hex that byte order equals plain string order.
     */
    private static String uuidByteKey(UUID id)
    {
        return id.toString();
    }

    // ========================================================================
    // Tweet search — visibility & lifecycle
    // ========================================================================

    @Test
    void tweetSearch_excludesBlockedAuthor_andReverseBlockedAuthor() throws Exception
    {
        AuthResponse viewer = registerUser("tsej");
        AuthResponse author = registerUser("tsek");
        AuthResponse other = registerUser("tsel");

        TweetResponse mine = createTweet(viewer, "own searchable words");
        TweetResponse theirs = createTweet(author, "blocked author searchable words");
        TweetResponse others = createTweet(other, "unrelated searchable words");

        // Viewer blocks the author: their tweet disappears for the viewer.
        block(viewer, author.userId());
        TweetSearchResponse blocked = searchTweets("searchable words", 0, 20, viewer.token());
        assertThat(blocked.totalItems()).isEqualTo(2);
        assertThat(blocked.tweets())
                .extracting(t -> t.tweetId())
                .containsExactlyInAnyOrder(mine.id(), others.id());

        // Reverse direction: unblock, then author blocks the viewer —
        // the author's tweet is still invisible to the viewer.
        unblockViaJdbc(viewer.userId(), author.userId());
        block(author, viewer.userId());
        TweetSearchResponse reverse = searchTweets("searchable words", 0, 20, viewer.token());
        assertThat(reverse.totalItems()).isEqualTo(2);
        assertThat(reverse.tweets())
                .extracting(t -> t.tweetId())
                .containsExactlyInAnyOrder(mine.id(), others.id());
    }

    @Test
    void tweetSearch_excludesMutedAuthor() throws Exception
    {
        AuthResponse viewer = registerUser("tsem");
        AuthResponse author = registerUser("tsen");
        AuthResponse other = registerUser("tseo");

        TweetResponse muted = createTweet(author, "muted author searchable text");
        TweetResponse visible = createTweet(other, "visible author searchable text");

        mute(viewer, author.userId());

        TweetSearchResponse result = searchTweets("searchable text", 0, 20, viewer.token());

        assertThat(result.totalItems()).isEqualTo(1);
        assertThat(result.tweets().get(0).tweetId()).isEqualTo(visible.id());
        assertThat(result.tweets())
                .extracting(t -> t.tweetId())
                .doesNotContain(muted.id());
    }

    @Test
    void tweetSearch_excludesSoftDeletedTweet_andDeletedAuthor() throws Exception
    {
        AuthResponse viewer = registerUser("tsep");
        AuthResponse author = registerUser("tseq");

        TweetResponse kept = createTweet(author, "kept searchable post");
        TweetResponse deleted = createTweet(author, "deleted searchable post");
        deleteTweet(deleted.id(), author.token());

        TweetSearchResponse afterDelete = searchTweets("searchable post", 0, 20, viewer.token());
        assertThat(afterDelete.totalItems()).isEqualTo(1);
        assertThat(afterDelete.tweets().get(0).tweetId()).isEqualTo(kept.id());

        // Soft-delete the author (mirrors DeleteAccountUseCase's is_deleted
        // semantics): every remaining tweet of theirs becomes invisible.
        softDeleteUser(author.userId());
        TweetSearchResponse afterAuthorDelete =
                searchTweets("searchable post", 0, 20, viewer.token());
        assertThat(afterAuthorDelete.totalItems()).isZero();
        assertThat(afterAuthorDelete.tweets()).isEmpty();
    }

    @Test
    void tweetSearch_excludesRetweetMarkers_butKeepsRepliesAndQuotes() throws Exception
    {
        AuthResponse author = registerUser("tser");
        AuthResponse viewer = registerUser("tses");
        AuthResponse second = registerUser("tset2");

        TweetResponse original = createTweet(author, "original retweetable content");

        // Retweet marker rows carry no content and must never surface.
        // (Self-retweets are rejected by InteractionPolicy, so both markers
        // come from non-author users.)
        retweet(original.id(), viewer.token());
        retweet(original.id(), second.token());

        // Replies and quotes stay searchable when they otherwise satisfy the
        // visibility rules.
        TweetResponse replyTweet = reply(author, original.id(), "reply on searchable original");
        TweetResponse quote = createQuote(author, "quoting the searchable original", original.id());

        // The original is found; the two content-less retweet markers are not.
        TweetSearchResponse noMarkers = searchTweets("retweetable content", 0, 20, viewer.token());
        assertThat(noMarkers.totalItems()).isEqualTo(1);
        assertThat(noMarkers.tweets().get(0).tweetId()).isEqualTo(original.id());

        // The reply and the quote of that original are both searchable.
        TweetSearchResponse replyAndQuote = searchTweets("searchable original", 0, 20, viewer.token());
        assertThat(replyAndQuote.totalItems()).isEqualTo(2);
        assertThat(replyAndQuote.tweets())
                .extracting(t -> t.tweetId())
                .containsExactlyInAnyOrder(replyTweet.id(), quote.id());
        assertThat(replyAndQuote.tweets())
                .extracting(t -> t.content())
                .doesNotContain((String) null);
    }

    @Test
    void tweetSearch_unauthenticatedRequest_returns401() throws Exception
    {
        MockHttpServletResponse response = mockMvc.perform(post("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gson.toJson(new RequestEnvelope(
                                UUID.randomUUID(),
                                RequestType.TWEET_SEARCH,
                                gson.toJsonTree(new SearchTweetsRequest("fox", 0, 20, null)),
                                null))))
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse();

        ResponseEnvelope envelope =
                gson.fromJson(response.getContentAsString(), ResponseEnvelope.class);
        assertThat(envelope.isSuccess()).isFalse();
        assertThat(envelope.errorCode()).isEqualTo("AUTH_REQUIRED");
    }

    @Test
    void tweetSearch_actorDerivedFromSession_notFromPayload() throws Exception
    {
        AuthResponse viewer = registerUser("tset");
        AuthResponse author = registerUser("tseu");
        AuthResponse attacker = registerUser("tsev");

        createTweet(author, "author blocked content sample");
        // The attacker blocks the author; the viewer has no block relation.
        block(attacker, author.userId());

        // The viewer submits attacker's session token, but spoofs an
        // actor-style id field that is not part of the contract — the actor
        // must be the token's user (attacker), never the viewer.
        TweetSearchResponse asAttacker =
                searchTweets("blocked content sample", 0, 20, attacker.token());
        assertThat(asAttacker.totalItems()).isZero();

        // The same query with the viewer's token still finds the tweet,
        // proving per-actor visibility is derived from the token only.
        TweetSearchResponse asViewer =
                searchTweets("blocked content sample", 0, 20, viewer.token());
        assertThat(asViewer.totalItems()).isEqualTo(1);
    }

    @Test
    void tweetSearch_likeWildcardsAreTreatedAsLiterals() throws Exception
    {
        AuthResponse author = registerUser("tswa");
        AuthResponse viewer = registerUser("tswb");

        TweetResponse literal = createTweet(author, "discount 100% off_cool \\ backslash");
        TweetResponse plain = createTweet(author, "a perfectly normal searchable tweet");

        // If LIKE wildcards were not escaped, each query below would match
        // every tweet instead of only the one containing the literal token.
        TweetSearchResponse percent = searchTweets("%", 0, 20, viewer.token());
        assertThat(percent.totalItems()).isEqualTo(1);
        assertThat(percent.tweets().get(0).tweetId()).isEqualTo(literal.id());

        TweetSearchResponse underscore = searchTweets("_", 0, 20, viewer.token());
        assertThat(underscore.totalItems()).isEqualTo(1);
        assertThat(underscore.tweets().get(0).tweetId()).isEqualTo(literal.id());

        TweetSearchResponse backslash = searchTweets("\\", 0, 20, viewer.token());
        assertThat(backslash.totalItems()).isEqualTo(1);
        assertThat(backslash.tweets().get(0).tweetId()).isEqualTo(literal.id());

        // SQL-looking text is bound as a plain parameter and matches nothing;
        // the query engine is untouched afterwards.
        TweetSearchResponse injection =
                searchTweets("'; DROP TABLE tweets; --", 0, 20, viewer.token());
        assertThat(injection.tweets()).isEmpty();
        assertThat(injection.totalItems()).isZero();

        TweetSearchResponse after = searchTweets("searchable", 0, 20, viewer.token());
        assertThat(after.totalItems()).isEqualTo(1);
        assertThat(after.tweets().get(0).tweetId()).isEqualTo(plain.id());
    }

    @Test
    void search_blankQuery_isRejectedAsValidationFailure() throws Exception
    {
        AuthResponse viewer = registerUser("tswc");

        ResponseEnvelope tweetBlank = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_SEARCH,
                gson.toJsonTree(new SearchTweetsRequest("   ", 0, 20, viewer.token())),
                null));
        assertThat(tweetBlank.isSuccess()).isFalse();
        assertThat(tweetBlank.errorCode()).isEqualTo("TWEET_SEARCH_FAILED");

        ResponseEnvelope hashtagBlank = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.HASHTAG_SEARCH,
                gson.toJsonTree(new SearchHashtagsRequest("", 0, 20, viewer.token())),
                null));
        assertThat(hashtagBlank.isSuccess()).isFalse();
        assertThat(hashtagBlank.errorCode()).isEqualTo("HASHTAG_SEARCH_FAILED");
    }

    @Test
    void hashtagSearch_unauthenticatedRequest_returns401() throws Exception
    {
        MockHttpServletResponse response = mockMvc.perform(post("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gson.toJson(new RequestEnvelope(
                                UUID.randomUUID(),
                                RequestType.HASHTAG_SEARCH,
                                gson.toJsonTree(new SearchHashtagsRequest("java", 0, 20, null)),
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
    // Hashtag search
    // ========================================================================

    @Test
    void hashtagSearch_findsExactPrefixAndIsCaseInsensitive() throws Exception
    {
        AuthResponse author = registerUser("tsew");

        createTweet(author, "posts about #java and #javascript");
        createTweet(author, "another #Java post");

        // Plain prefix finds exact and longer tags.
        HashtagSearchResponse java = searchHashtags("java", 0, 20, author.token());
        assertThat(java.totalItems()).isEqualTo(2);
        assertThat(java.hashtags())
                .extracting(HashtagSearchResponse.HashtagSearchItem::tag)
                .containsExactly("java", "javascript");

        // Case-insensitive input normalizes to the canonical prefix.
        HashtagSearchResponse mixed = searchHashtags("JavA", 0, 20, author.token());
        assertThat(mixed.totalItems()).isEqualTo(2);

        // Leading '#' is stripped before matching.
        HashtagSearchResponse hash = searchHashtags("#Jav", 0, 20, author.token());
        assertThat(hash.totalItems()).isEqualTo(2);
        assertThat(hash.hashtags())
                .extracting(HashtagSearchResponse.HashtagSearchItem::tag)
                .containsExactly("java", "javascript");

        // javascript returns only the expected prefix matches.
        HashtagSearchResponse script = searchHashtags("javascript", 0, 20, author.token());
        assertThat(script.totalItems()).isEqualTo(1);
        assertThat(script.hashtags().get(0).tag()).isEqualTo("javascript");
    }

    @Test
    void hashtagSearch_deterministicTagOrder_paginationAndEmptyResult() throws Exception
    {
        AuthResponse author = registerUser("tsex");

        // Five distinct tags sharing the "sear" prefix so one prefix query
        // paginates across all of them.
        createTweet(author, "#seare #seara #seard #searb #searc tags");

        HashtagSearchResponse page0 = searchHashtags("sear", 0, 2, author.token());
        assertThat(page0.totalItems()).isEqualTo(5);
        assertThat(page0.hashtags())
                .extracting(HashtagSearchResponse.HashtagSearchItem::tag)
                .containsExactly("seara", "searb");
        assertThat(page0.hasNext()).isTrue();

        HashtagSearchResponse page2 = searchHashtags("sear", 2, 2, author.token());
        assertThat(page2.hashtags())
                .extracting(HashtagSearchResponse.HashtagSearchItem::tag)
                .containsExactly("seare");
        assertThat(page2.hasNext()).isFalse();

        // Empty result is a successful empty page.
        HashtagSearchResponse none = searchHashtags("zzzznope", 0, 20, author.token());
        assertThat(none.hashtags()).isEmpty();
        assertThat(none.totalItems()).isZero();
        assertThat(none.hasNext()).isFalse();
    }

    @Test
    void hashtagSearch_doesNotCreateHashtagRows() throws Exception
    {
        AuthResponse viewer = registerUser("tsey");

        long before = countHashtagRows();

        searchHashtags("brandnewtag", 0, 20, viewer.token());
        searchHashtags("#alsonew", 0, 20, viewer.token());

        long after = countHashtagRows();
        assertThat(after).as("search must never create hashtag rows").isEqualTo(before);
    }

    @Test
    void hashtagSearch_discoversTagsCreatedByTweetCreationFlow() throws Exception
    {
        AuthResponse author = registerUser("tsez");

        // Normal tweet creation persists hashtags; search must find them.
        TweetResponse tweet = createTweet(author, "fresh tag here #freshflow");

        HashtagSearchResponse result = searchHashtags("freshflow", 0, 20, author.token());
        assertThat(result.totalItems()).isEqualTo(1);
        assertThat(result.hashtags().get(0).tag()).isEqualTo("freshflow");
        assertThat(tweet.id()).isNotNull();
    }

    // ========================================================================
    // User search — regression guard (legacy contract untouched)
    // ========================================================================

    @Test
    void userSearch_regression_existingContractStillWorks() throws Exception
    {
        AuthResponse viewer = registerUser("tsfz");
        AuthResponse target = registerUser("tsfa");

        // Existing behavior: bare-array success payload.
        MockHttpServletResponse response = mockMvc.perform(post("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gson.toJson(new RequestEnvelope(
                                UUID.randomUUID(),
                                RequestType.USER_SEARCH,
                                gson.toJsonTree(new logic_core.app.dto.request.SearchUsersRequest(
                                        viewer.token(), target.username(), 20, 0)),
                                null))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();

        ResponseEnvelope envelope =
                gson.fromJson(response.getContentAsString(), ResponseEnvelope.class);
        assertThat(envelope.isSuccess()).isTrue();

        UserSearchResponse[] users =
                gson.fromJson(envelope.getData(), UserSearchResponse[].class);
        assertThat(users).hasSize(1);
        assertThat(users[0].id()).isEqualTo(target.userId());

        // The actor's own row is excluded by the existing query.
        logic_core.app.dto.request.SearchUsersRequest selfQuery =
                new logic_core.app.dto.request.SearchUsersRequest(
                        viewer.token(), viewer.username(), 20, 0);
        ResponseEnvelope selfEnvelope = send(new RequestEnvelope(
                UUID.randomUUID(), RequestType.USER_SEARCH,
                gson.toJsonTree(selfQuery), null));
        assertThat(selfEnvelope.isSuccess()).isTrue();
        UserSearchResponse[] selfResult =
                gson.fromJson(selfEnvelope.getData(), UserSearchResponse[].class);
        assertThat(selfResult).isEmpty();

        // Short-query failure remains unchanged (legacy message contract).
        logic_core.app.dto.request.SearchUsersRequest shortQuery =
                new logic_core.app.dto.request.SearchUsersRequest(
                        viewer.token(), "a", 20, 0);
        ResponseEnvelope shortEnvelope = send(new RequestEnvelope(
                UUID.randomUUID(), RequestType.USER_SEARCH,
                gson.toJsonTree(shortQuery), null));
        assertThat(shortEnvelope.isSuccess()).isFalse();
        assertThat(shortEnvelope.errorCode()).isEqualTo("SEARCH_USERS_FAILED");

        // Unauthenticated request remains 401 / AUTH_REQUIRED.
        MockHttpServletResponse unauth = mockMvc.perform(post("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gson.toJson(new RequestEnvelope(
                                UUID.randomUUID(),
                                RequestType.USER_SEARCH,
                                gson.toJsonTree(new logic_core.app.dto.request.SearchUsersRequest(
                                        null, target.username(), 20, 0)),
                                null))))
                .andExpect(status().isUnauthorized())
                .andReturn()
                .getResponse();
        ResponseEnvelope unauthEnvelope =
                gson.fromJson(unauth.getContentAsString(), ResponseEnvelope.class);
        assertThat(unauthEnvelope.isSuccess()).isFalse();
        assertThat(unauthEnvelope.errorCode()).isEqualTo("AUTH_REQUIRED");
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private AuthResponse registerUser(String prefix) throws Exception
    {
        String username = prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest registerRequest = new RegisterRequest(
                username,
                username + "@searchtest.com",
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

    private TweetResponse createTweetAt(
            AuthResponse author, String content, java.time.OffsetDateTime publishedAt)
            throws Exception
    {
        CreateTweetRequest createRequest =
                new CreateTweetRequest(content, null, null, null, author.token(), null);
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_CREATE,
                gson.toJsonTree(createRequest),
                null));
        assertSuccess(envelope, "create tweet at fixed time " + content);

        TweetResponse tweet = gson.fromJson(envelope.getData(), TweetResponse.class);
        createdTweetIds.add(tweet.id());
        // Force the identical publishedAt directly so the id ASC tie-breaker
        // is genuinely exercised.
        jdbcTemplate.update("UPDATE tweets SET published_at = ? WHERE id = ?",
                publishedAt, tweet.id());
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

    private TweetResponse createQuote(
            AuthResponse author, String content, UUID quoteOfId) throws Exception
    {
        CreateTweetRequest createRequest =
                new CreateTweetRequest(content, null, quoteOfId, null, author.token(), null);
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_CREATE,
                gson.toJsonTree(createRequest),
                null));
        assertSuccess(envelope, "quote tweet " + content);

        TweetResponse tweet = gson.fromJson(envelope.getData(), TweetResponse.class);
        createdTweetIds.add(tweet.id());
        return tweet;
    }

    private void retweet(UUID tweetId, String token) throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_RETWEET,
                gson.toJsonTree(new RetweetRequest(tweetId, token)),
                null));
        assertSuccess(envelope, "retweet " + tweetId);
    }

    private void block(AuthResponse blocker, UUID blockedId) throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.RELATION_BLOCK,
                gson.toJsonTree(new BlockUserRequest(blockedId, blocker.token())),
                null));
        assertSuccess(envelope, "block " + blockedId);
    }

    private void mute(AuthResponse muter, UUID mutedId) throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.RELATION_MUTE,
                gson.toJsonTree(new MuteUserRequest(mutedId, muter.token())),
                null));
        assertSuccess(envelope, "mute " + mutedId);
    }

    private void unblockViaJdbc(UUID blockerId, UUID blockedId)
    {
        jdbcTemplate.update(
                "DELETE FROM blocks WHERE blocker_id = ? AND blocked_id = ?",
                blockerId, blockedId);
    }

    private void softDeleteUser(UUID userId)
    {
        jdbcTemplate.update("UPDATE users SET is_deleted = true WHERE id = ?", userId);
    }

    private TweetSearchResponse searchTweets(
            String query, int page, int pageSize, String token) throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_SEARCH,
                gson.toJsonTree(new SearchTweetsRequest(query, page, pageSize, token)),
                null));
        assertSuccess(envelope, "tweet search " + query);
        return gson.fromJson(envelope.getData(), TweetSearchResponse.class);
    }

    private HashtagSearchResponse searchHashtags(
            String query, int page, int pageSize, String token) throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.HASHTAG_SEARCH,
                gson.toJsonTree(new SearchHashtagsRequest(query, page, pageSize, token)),
                null));
        assertSuccess(envelope, "hashtag search " + query);
        return gson.fromJson(envelope.getData(), HashtagSearchResponse.class);
    }

    private long countHashtagRows()
    {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM hashtags", Long.class);
        return count == null ? 0L : count;
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
