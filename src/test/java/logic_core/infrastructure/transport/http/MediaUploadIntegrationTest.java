package logic_core.infrastructure.transport.http;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import logic_core.app.dto.media.UploadFile;
import logic_core.app.dto.request.CreateTweetRequest;
import logic_core.app.dto.request.DeleteMediaRequest;
import logic_core.app.dto.request.GetBookmarksRequest;
import logic_core.app.dto.request.GetHashtagTweetsRequest;
import logic_core.app.dto.request.GetRepliesRequest;
import logic_core.app.dto.request.GetTimelineRequest;
import logic_core.app.dto.request.GetTimelineResponse;
import logic_core.app.dto.request.GetTweetRequest;
import logic_core.app.dto.request.RegisterRequest;
import logic_core.app.dto.request.ReplyTweetRequest;
import logic_core.app.dto.request.UploadMediaRequest;
import logic_core.app.dto.response.AuthResponse;
import logic_core.app.dto.response.GetBookmarksResponse;
import logic_core.app.dto.response.HashtagTweetsResponse;
import logic_core.app.dto.response.MediaResponse;
import logic_core.app.dto.response.TweetResponse;
import logic_core.app.dto.response.UploadMediaResponse;
import logic_core.app.dto.timeline.TimelineMedia;
import logic_core.app.dto.timeline.TimelineTweet;
import logic_core.domain.model.media.MediaType;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration suite for the V2.1 #6 media upload + attachment flow.
 *
 * <p>Drives the real application path:
 * {@code POST /api} {@link RequestType#MEDIA_UPLOAD} → {@link logic_core.app.facade.MediaFacade}
 * → {@link logic_core.app.usecase.media.UploadMediaUseCase} → {@link logic_core.domain.service.MediaStorageService}
 * → {@link logic_core.domain.repository.MediaRepository} → JPA → PostgreSQL.
 *
 * <p>It also verifies that an authenticated owner can attach their uploaded media
 * to a tweet or reply, and that attached media is surfaced by the existing tweet
 * read models: {@code TWEET_GET}, timeline, replies, bookmarks, and hashtag feed.
 *
 * <p>Uses the same disposable-test-body cleanup pattern as the other HTTP
 * integration tests: every created tweet and user is removed in
 * {@link #cleanUpCreatedRows()}. Media rows are removed via FK cascade from
 * {@code tweets}.
 */
@SpringBootTest(classes = ServerMain.class)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MediaUploadIntegrationTest
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
    // Upload
    // ========================================================================

    @Test
    void uploadMedia_authenticatedUser_persistsUnattachedMedia()
            throws Exception
    {
        AuthResponse user = registerUser("upload");

        byte[] png = validPngBytes();
        UploadFile file = new UploadFile("avatar.png", "image/png", png);

        UploadMediaResponse[] uploaded = new UploadMediaResponse[1];
        long createdRows = withMediaRowCount(() -> uploaded[0] = uploadMedia(user, file));
        UploadMediaResponse response = uploaded[0];

        assertThat(response.mediaId()).isNotNull();
        assertThat(response.mediaUrl()).isNotEmpty();
        assertThat(response.originalFilename()).isEqualTo("avatar.png");
        assertThat(response.fileSizeBytes()).isEqualTo((long) png.length);
        assertThat(response.mediaType()).isEqualTo(logic_core.domain.model.media.MediaType.IMAGE);

        assertThat(createdRows).isEqualTo(1L);
        assertThat(mediaTweetId(response.mediaId())).isNull();
        assertThat(mediaUploadedBy(response.mediaId())).isEqualTo(user.userId());
        assertThat(storedTweetMediaFileExists(response.mediaUrl())).isTrue();
    }

    @Test
    void uploadMedia_supportedVideo_persistsUnattachedMedia()
            throws Exception
    {
        AuthResponse user = registerUser("upload");

        UploadFile file = new UploadFile("clip.mp4", "video/mp4", new byte[]{1, 2, 3});

        UploadMediaResponse response = uploadMedia(user, file);

        assertThat(response.mediaType()).isEqualTo(logic_core.domain.model.media.MediaType.VIDEO);
        assertThat(mediaUploadedBy(response.mediaId())).isEqualTo(user.userId());
        assertThat(mediaTweetId(response.mediaId())).isNull();
    }

    @Test
    void uploadMedia_uploadedByIsDerivedFromAuthContext()
            throws Exception
    {
        AuthResponse alice = registerUser("upload");
        AuthResponse bob = registerUser("upload");

        UploadFile file = new UploadFile("shared.png", "image/png", new byte[]{1});

        UploadMediaResponse aliceResponse = uploadMedia(alice, file);
        assertThat(mediaUploadedBy(aliceResponse.mediaId())).isEqualTo(alice.userId());
        assertThat(mediaUploadedBy(aliceResponse.mediaId())).isNotEqualTo(bob.userId());

        UploadMediaResponse bobResponse = uploadMedia(bob, file);
        assertThat(mediaUploadedBy(bobResponse.mediaId())).isEqualTo(bob.userId());
    }

    @Test
    void uploadMedia_unauthenticatedFails()
            throws Exception
    {
        UploadFile file = new UploadFile("x.png", "image/png", new byte[]{1});

        // The HTTP contract for unauthenticated access (V2.0 #5) is HTTP 401
        // carrying the standard failure envelope, matching the other suites.
        org.springframework.mock.web.MockHttpServletResponse httpResponse =
                mockMvc.perform(post("/api")
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(gson.toJson(new RequestEnvelope(
                                        UUID.randomUUID(),
                                        RequestType.MEDIA_UPLOAD,
                                        gson.toJsonTree(new UploadMediaRequest(null, file)),
                                        null))))
                        .andExpect(status().isUnauthorized())
                        .andReturn()
                        .getResponse();

        ResponseEnvelope response = gson.fromJson(
                httpResponse.getContentAsString(), ResponseEnvelope.class);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.errorCode()).isEqualTo("AUTH_REQUIRED");
    }

    @Test
    void uploadMedia_emptyFile_fails()
            throws Exception
    {
        AuthResponse user = registerUser("upload");

        ResponseEnvelope[] sent = new ResponseEnvelope[1];
        long createdRows = withMediaRowCount(() -> sent[0] = send(
                new RequestEnvelope(
                        UUID.randomUUID(),
                        RequestType.MEDIA_UPLOAD,
                        gson.toJsonTree(new UploadMediaRequest(
                                user.token(),
                                new UploadFile("empty.png", "image/png", new byte[0]))),
                        null)));
        ResponseEnvelope response = sent[0];

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.errorCode()).isEqualTo("UPLOAD_MEDIA_FAILED");
        assertThat(response.errorMessage()).containsIgnoringCase("empty");
        assertThat(createdRows).isZero();
    }

    @Test
    void uploadMedia_unsupportedType_fails()
            throws Exception
    {
        AuthResponse user = registerUser("upload");

        ResponseEnvelope[] sent = new ResponseEnvelope[1];
        long createdRows = withMediaRowCount(() -> sent[0] = send(
                new RequestEnvelope(
                        UUID.randomUUID(),
                        RequestType.MEDIA_UPLOAD,
                        gson.toJsonTree(new UploadMediaRequest(
                                user.token(),
                                new UploadFile("doc.pdf", "application/pdf", new byte[]{1}))),
                        null)));
        ResponseEnvelope response = sent[0];

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.errorCode()).isEqualTo("UPLOAD_MEDIA_FAILED");
        assertThat(response.errorMessage()).containsIgnoringCase("unsupported");
        assertThat(createdRows).isZero();
    }

    @Test
    void uploadMedia_svgContentType_fails()
            throws Exception
    {
        AuthResponse user = registerUser("upload");

        // image/svg+xml sits inside the allowed image/ family but is not on
        // the storage allowlist (scriptable SVG is a stored-XSS vector), so
        // the upload must be rejected outright.
        ResponseEnvelope[] sent = new ResponseEnvelope[1];
        long createdRows = withMediaRowCount(() -> sent[0] = send(
                new RequestEnvelope(
                        UUID.randomUUID(),
                        RequestType.MEDIA_UPLOAD,
                        gson.toJsonTree(new UploadMediaRequest(
                                user.token(),
                                new UploadFile("vector.svg", "image/svg+xml", new byte[]{1, 2, 3}))),
                        null)));
        ResponseEnvelope response = sent[0];

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.errorCode()).isEqualTo("UPLOAD_MEDIA_FAILED");
        assertThat(response.errorMessage()).containsIgnoringCase("unsupported");
        assertThat(createdRows).isZero();
    }

    @Test
    void uploadMedia_tiffContentType_fails()
            throws Exception
    {
        AuthResponse user = registerUser("upload");

        ResponseEnvelope[] sent = new ResponseEnvelope[1];
        long createdRows = withMediaRowCount(() -> sent[0] = send(
                new RequestEnvelope(
                        UUID.randomUUID(),
                        RequestType.MEDIA_UPLOAD,
                        gson.toJsonTree(new UploadMediaRequest(
                                user.token(),
                                new UploadFile("scan.tiff", "image/tiff", new byte[]{1, 2, 3}))),
                        null)));
        ResponseEnvelope response = sent[0];

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.errorCode()).isEqualTo("UPLOAD_MEDIA_FAILED");
        assertThat(response.errorMessage()).containsIgnoringCase("unsupported");
        assertThat(createdRows).isZero();
    }

    @Test
    void uploadMedia_imageTypeWithHtmlFilename_storesDerivedExtension()
            throws Exception
    {
        AuthResponse user = registerUser("upload");

        // The content type is allowlisted (png) but the filename carries a
        // dangerous extension; storage must derive .png from the validated
        // type and never persist the client-supplied .html extension.
        UploadMediaResponse response = uploadMedia(
                user,
                new UploadFile("payload.html", "image/png", new byte[]{1, 2, 3}));

        assertThat(response.mediaType())
                .isEqualTo(logic_core.domain.model.media.MediaType.IMAGE);
        assertThat(response.mediaUrl()).endsWith(".png");
        assertThat(response.mediaUrl()).doesNotContain(".html");
        assertThat(storedTweetMediaFileExists(response.mediaUrl())).isTrue();

        // Self-cleanup through the delete path (removes row + physical file).
        ResponseEnvelope deleteEnvelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.MEDIA_DELETE,
                gson.toJsonTree(new DeleteMediaRequest(response.mediaId(), user.token())),
                null));
        assertSuccess(deleteEnvelope, "delete html-named media");

        assertThat(storedTweetMediaFileExists(response.mediaUrl())).isFalse();
    }

    @Test
    void uploadMedia_gifContentType_stillSupported()
            throws Exception
    {
        AuthResponse user = registerUser("upload");

        UploadMediaResponse response = uploadMedia(
                user,
                new UploadFile("anim.gif", "image/gif", new byte[]{1, 2, 3}));

        assertThat(response.mediaType())
                .isEqualTo(logic_core.domain.model.media.MediaType.GIF);
        assertThat(response.mediaUrl()).endsWith(".gif");

        // Self-cleanup through the delete path (removes row + physical file).
        ResponseEnvelope deleteEnvelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.MEDIA_DELETE,
                gson.toJsonTree(new DeleteMediaRequest(response.mediaId(), user.token())),
                null));
        assertSuccess(deleteEnvelope, "delete gif media");

        assertThat(storedTweetMediaFileExists(response.mediaUrl())).isFalse();
    }

    // ========================================================================
    // Attachment
    // ========================================================================

    @Test
    void uploadThenAttachToTweet_byOwner_succeeds()
            throws Exception
    {
        AuthResponse user = registerUser("attach");

        UploadFile png = new UploadFile("attach.png", "image/png", new byte[]{1, 2, 3});
        UploadMediaResponse uploaded = uploadMedia(user, png);

        TweetResponse tweet = createTweet(user, "attached", null, List.of(uploaded.mediaId().toString()));

        assertThat(tweet.media()).hasSize(1);
        MediaResponse attached = tweet.media().get(0);
        assertThat(attached.mediaId()).isEqualTo(uploaded.mediaId());
        assertThat(attached.mediaUrl()).isEqualTo(uploaded.mediaUrl());
        assertThat(attached.mediaType()).isEqualTo(logic_core.domain.model.media.MediaType.IMAGE);

        assertThat(mediaTweetId(uploaded.mediaId())).isEqualTo(tweet.id());
    }

    @Test
    void uploadThenAttachMultipleMedia_toTweet_preservesOrder()
            throws Exception
    {
        AuthResponse user = registerUser("attach");

        UploadMediaResponse first = uploadMedia(user, new UploadFile("a.png", "image/png", new byte[]{1}));
        UploadMediaResponse second = uploadMedia(user, new UploadFile("b.png", "image/png", new byte[]{2}));
        UploadMediaResponse third = uploadMedia(user, new UploadFile("c.png", "image/png", new byte[]{3}));

        TweetResponse tweet = createTweet(
                user,
                "multi",
                null,
                List.of(third.mediaId().toString(), first.mediaId().toString(), second.mediaId().toString()));

        assertThat(tweet.media()).hasSize(3);
        assertThat(tweet.media().get(0).displayOrder()).isEqualTo((short) 0);
        assertThat(tweet.media().get(0).mediaId()).isEqualTo(first.mediaId());
        assertThat(tweet.media().get(1).displayOrder()).isEqualTo((short) 1);
        assertThat(tweet.media().get(1).mediaId()).isEqualTo(second.mediaId());
        assertThat(tweet.media().get(2).displayOrder()).isEqualTo((short) 2);
        assertThat(tweet.media().get(2).mediaId()).isEqualTo(third.mediaId());
    }

    @Test
    void tweetWithoutMedia_stillWorks()
            throws Exception
    {
        AuthResponse user = registerUser("attach");

        TweetResponse tweet = createTweet(user, "no media", null, null);

        assertThat(tweet.media()).isEmpty();
        assertThat(tweet.id()).isNotNull();
    }

    @Test
    void ownerCanAttachOwnMediaToReply()
            throws Exception
    {
        AuthResponse user = registerUser("attach");
        TweetResponse parent = createTweet(user, "parent", null, null);

        UploadMediaResponse uploaded = uploadMedia(user, new UploadFile("r.png", "image/png", new byte[]{1}));
        TweetResponse reply = createReply(user, parent.id(), "reply with media", List.of(uploaded.mediaId().toString()));

        assertThat(reply.media()).hasSize(1);
        assertThat(reply.media().get(0).mediaId()).isEqualTo(uploaded.mediaId());
        assertThat(mediaTweetId(uploaded.mediaId())).isEqualTo(reply.id());
    }

    @Test
    void cannotAttachAnotherUsersMedia()
            throws Exception
    {
        AuthResponse alice = registerUser("attach");
        AuthResponse bob = registerUser("attach");

        UploadMediaResponse aliceMedia = uploadMedia(alice, new UploadFile("mine.png", "image/png", new byte[]{1}));

        ResponseEnvelope response = send(
                new RequestEnvelope(
                        UUID.randomUUID(),
                        RequestType.TWEET_CREATE,
                        gson.toJsonTree(new CreateTweetRequest(
                                "bob tries alice media",
                                null,
                                null,
                                null,
                                bob.token(),
                                List.of(aliceMedia.mediaId().toString()))),
                        null));

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.errorCode()).isEqualTo("TWEET_CREATE_FAILED");
        assertThat(response.errorMessage()).containsIgnoringCase("ownership");
        assertThat(mediaTweetId(aliceMedia.mediaId())).isNull();
    }

    @Test
    void cannotAttachNonexistentMedia()
            throws Exception
    {
        AuthResponse user = registerUser("attach");

        ResponseEnvelope response = send(
                new RequestEnvelope(
                        UUID.randomUUID(),
                        RequestType.TWEET_CREATE,
                        gson.toJsonTree(new CreateTweetRequest(
                                "bad media id",
                                null,
                                null,
                                null,
                                user.token(),
                                List.of(UUID.randomUUID().toString()))),
                        null));

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.errorCode()).isEqualTo("TWEET_CREATE_FAILED");
        assertThat(response.errorMessage()).containsIgnoringCase("nonexistent");
    }

    @Test
    void cannotAttachAlreadyAttachedMedia()
            throws Exception
    {
        AuthResponse user = registerUser("attach");

        UploadMediaResponse uploaded = uploadMedia(user, new UploadFile("once.png", "image/png", new byte[]{1}));
        createTweet(user, "first", null, List.of(uploaded.mediaId().toString()));

        ResponseEnvelope response = send(
                new RequestEnvelope(
                        UUID.randomUUID(),
                        RequestType.TWEET_CREATE,
                        gson.toJsonTree(new CreateTweetRequest(
                                "second attach",
                                null,
                                null,
                                null,
                                user.token(),
                                List.of(uploaded.mediaId().toString()))),
                        null));

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.errorCode()).isEqualTo("TWEET_CREATE_FAILED");
        assertThat(response.errorMessage()).containsIgnoringCase("already attached");
        assertThat(mediaTweetId(uploaded.mediaId())).isNotNull();
    }

    // ========================================================================
    // Read models
    // ========================================================================

    @Test
    void singleTweetRead_surfacesAttachedMedia()
            throws Exception
    {
        AuthResponse author = registerUser("read");
        UploadMediaResponse uploaded = uploadMedia(author, new UploadFile("s.png", "image/png", new byte[]{1}));
        TweetResponse tweet = createTweet(author, "single", null, List.of(uploaded.mediaId().toString()));

        TimelineTweet single = getSingleTweet(author.token(), tweet.id());

        assertThat(single.media()).hasSize(1);
        assertThat(single.media().get(0).mediaId()).isEqualTo(uploaded.mediaId());
        assertThat(single.media().get(0).mediaUrl()).isEqualTo(uploaded.mediaUrl());
        assertThat(single.media().get(0).mediaType()).isEqualTo(logic_core.domain.model.media.MediaType.IMAGE);
    }

    @Test
    void homeTimeline_surfacesAttachedMedia()
            throws Exception
    {
        AuthResponse author = registerUser("read");
        AuthResponse me = registerUser("read");
        follow(me, author);
        UploadMediaResponse uploaded = uploadMedia(author, new UploadFile("t.png", "image/png", new byte[]{1}));
        createTweet(author, "timeline", null, List.of(uploaded.mediaId().toString()));

        TimelineTweet timelineTweet = findTimelineTweetByContent(me, "timeline");
        assertThat(timelineTweet).isNotNull();
        assertThat(timelineTweet.media()).hasSize(1);
        assertThat(timelineTweet.media().get(0).mediaId()).isEqualTo(uploaded.mediaId());
    }

    @Test
    void repliesFeed_surfacesAttachedMedia()
            throws Exception
    {
        AuthResponse author = registerUser("read");
        TweetResponse parent = createTweet(author, "parent", null, null);
        UploadMediaResponse uploaded = uploadMedia(author, new UploadFile("r.png", "image/png", new byte[]{1}));
        TweetResponse reply = createReply(author, parent.id(), "reply media", List.of(uploaded.mediaId().toString()));

        List<TimelineTweet> replies = getReplies(author.token(), parent.id());
        TimelineTweet found = replies.stream()
                .filter(t -> t.tweetId().equals(reply.id()))
                .findFirst()
                .orElseThrow();

        assertThat(found.media()).hasSize(1);
        assertThat(found.media().get(0).mediaId()).isEqualTo(uploaded.mediaId());
    }

    @Test
    void bookmarkFeed_surfacesAttachedMedia()
            throws Exception
    {
        AuthResponse author = registerUser("read");
        AuthResponse reader = registerUser("read");
        UploadMediaResponse uploaded = uploadMedia(author, new UploadFile("b.png", "image/png", new byte[]{1}));
        TweetResponse tweet = createTweet(author, "bookmark", null, List.of(uploaded.mediaId().toString()));
        bookmark(reader, tweet.id());

        List<TimelineTweet> bookmarks = getBookmarks(reader.token());
        TimelineTweet found = bookmarks.stream()
                .filter(t -> t.tweetId().equals(tweet.id()))
                .findFirst()
                .orElseThrow();

        assertThat(found.media()).hasSize(1);
        assertThat(found.media().get(0).mediaId()).isEqualTo(uploaded.mediaId());
    }

    @Test
    void hashtagFeed_surfacesAttachedMedia()
            throws Exception
    {
        AuthResponse author = registerUser("read");
        UploadMediaResponse uploaded = uploadMedia(author, new UploadFile("h.png", "image/png", new byte[]{1}));
        createTweet(author, "reading #mediatagdata", null, List.of(uploaded.mediaId().toString()));

        List<TimelineTweet> hashtags = getHashtagTweets("mediatagdata", author.token());
        TimelineTweet found = hashtags.stream()
                .filter(t -> t.content().contains("#mediatagdata"))
                .findFirst()
                .orElseThrow();

        assertThat(found.media()).hasSize(1);
        assertThat(found.media().get(0).mediaId()).isEqualTo(uploaded.mediaId());
    }

    @Test
    void deleteTweet_removesAttachedMediaRows()
            throws Exception
    {
        AuthResponse author = registerUser("read");
        UploadMediaResponse uploaded = uploadMedia(author, new UploadFile("d.png", "image/png", new byte[]{1}));
        TweetResponse tweet = createTweet(author, "delete me", null, List.of(uploaded.mediaId().toString()));

        UUID mediaId = uploaded.mediaId();
        assertThat(mediaTweetId(mediaId)).isEqualTo(tweet.id());

        deleteTweet(tweet.id(), author.token());

        // The attached media row itself must be gone; the table is shared with
        // other suites, so assert on this media's existence, not table size.
        assertThat(mediaExists(mediaId)).isFalse();
    }

    // ========================================================================
    // Deleting unattached media (V2.1 #6 upload-before-attach lifecycle)
    // ========================================================================

    @Test
    void deleteUnattachedMedia_byOwner_succeedsAndRemovesRowAndFile()
            throws Exception
    {
        AuthResponse user = registerUser("delete");

        UploadMediaResponse uploaded = uploadMedia(
                user,
                new UploadFile("gone.png", "image/png", new byte[]{1, 2, 3}));
        UUID mediaId = uploaded.mediaId();

        assertThat(mediaExists(mediaId)).isTrue();
        assertThat(storedTweetMediaFileExists(uploaded.mediaUrl())).isTrue();

        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.MEDIA_DELETE,
                gson.toJsonTree(new DeleteMediaRequest(mediaId, user.token())),
                null));

        assertSuccess(envelope, "delete unattached media");

        assertThat(mediaExists(mediaId)).isFalse();
        assertThat(storedTweetMediaFileExists(uploaded.mediaUrl())).isFalse();
    }

    @Test
    void deleteUnattachedMedia_byNonOwner_failsAndMediaRemains()
            throws Exception
    {
        AuthResponse alice = registerUser("delete");
        AuthResponse bob = registerUser("delete");

        UploadMediaResponse uploaded = uploadMedia(
                alice,
                new UploadFile("mine.png", "image/png", new byte[]{1}));
        UUID mediaId = uploaded.mediaId();

        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.MEDIA_DELETE,
                gson.toJsonTree(new DeleteMediaRequest(mediaId, bob.token())),
                null));

        assertThat(envelope.isSuccess()).isFalse();
        assertThat(envelope.errorCode()).isEqualTo("DELETE_MEDIA_FAILED");
        assertThat(envelope.errorMessage()).containsIgnoringCase("own");

        // Neither the row nor the stored file may disappear for a non-owner.
        assertThat(mediaExists(mediaId)).isTrue();
        assertThat(storedTweetMediaFileExists(uploaded.mediaUrl())).isTrue();

        // Test hygiene: the production code correctly left the file alone, so
        // remove it here to keep repeated runs from leaking orphan files.
        Files.deleteIfExists(Path.of("data/media")
                .resolve(uploaded.mediaUrl().replaceFirst("^/media/", "")));
    }

    @Test
    void deleteUnattachedMedia_nonExistentMedia_fails()
            throws Exception
    {
        AuthResponse user = registerUser("delete");

        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.MEDIA_DELETE,
                gson.toJsonTree(new DeleteMediaRequest(UUID.randomUUID(), user.token())),
                null));

        assertThat(envelope.isSuccess()).isFalse();
        assertThat(envelope.errorCode()).isEqualTo("DELETE_MEDIA_FAILED");
        assertThat(envelope.errorMessage()).containsIgnoringCase("not found");
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private AuthResponse registerUser(String prefix) throws Exception
    {
        String username = prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
        RegisterRequest registerRequest = new RegisterRequest(
                username,
                username + "@mediatest.com",
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

    private UploadMediaResponse uploadMedia(AuthResponse user, UploadFile file) throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.MEDIA_UPLOAD,
                gson.toJsonTree(new UploadMediaRequest(user.token(), file)),
                null));
        assertSuccess(envelope, "upload " + file.fileName());
        return gson.fromJson(envelope.getData(), UploadMediaResponse.class);
    }

    private TweetResponse createTweet(AuthResponse author, String content, String hashtagHint, List<String> mediaIdStrs)
            throws Exception
    {
        CreateTweetRequest createRequest = new CreateTweetRequest(
                content,
                null,
                null,
                null,
                author.token(),
                mediaIdStrs);
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_CREATE,
                gson.toJsonTree(createRequest),
                null));
        assertSuccess(envelope, "create tweet " + content);

        TweetResponse tweet = gson.fromJson(envelope.getData(), TweetResponse.class);
        createdTweetIds.add(tweet.id());
        if (hashtagHint != null)
        {
            recordTweetHashtagsForTest(tweet.id(), hashtagHint);
        }
        return tweet;
    }

    private TweetResponse createReply(AuthResponse user, UUID parentTweetId, String text, List<String> mediaIdStrs)
            throws Exception
    {
        ReplyTweetRequest replyRequest = new ReplyTweetRequest(
                parentTweetId,
                text,
                mediaIdStrs,
                user.token());
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

    private List<TimelineTweet> getBookmarks(String token) throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.BOOKMARKS_GET,
                gson.toJsonTree(new GetBookmarksRequest(0, 20, token)),
                null));
        assertSuccess(envelope, "get bookmarks");
        return gson.fromJson(envelope.getData(), GetBookmarksResponse.class)
                .tweets();
    }

    private void bookmark(AuthResponse reader, UUID tweetId) throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_BOOKMARK,
                gson.toJsonTree(                new logic_core.app.dto.request.BookmarkTweetRequest(tweetId, reader.token())),
                null));
        assertSuccess(envelope, "bookmark " + tweetId);
    }

    private List<TimelineTweet> getHashtagTweets(String tag, String token) throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.HASHTAG_GET_TWEETS,
                gson.toJsonTree(new GetHashtagTweetsRequest(tag, 0, 20, token)),
                null));
        assertSuccess(envelope, "hashtag feed " + tag);
        return gson.fromJson(envelope.getData(), HashtagTweetsResponse.class)
                .tweets();
    }

    private List<TimelineTweet> getHomeTimeline(AuthResponse user) throws Exception
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
        return gson.fromJson(envelope.getData(), GetTimelineResponse.class)
                .tweets();
    }

    private TimelineTweet findTimelineTweetByContent(AuthResponse user, String content) throws Exception
    {
        return getHomeTimeline(user).stream()
                .filter(t -> t.content().contains(content))
                .findFirst()
                .orElse(null);
    }

    private void follow(AuthResponse follower, AuthResponse followee) throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.RELATION_FOLLOW,
                gson.toJsonTree(                new logic_core.app.dto.request.FollowUserRequest(
                        followee.userId(),
                        follower.token())),
                null));
        assertSuccess(envelope, "follow");
    }

    private void deleteTweet(UUID tweetId, String token) throws Exception
    {
        ResponseEnvelope envelope = send(new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_DELETE,
                gson.toJsonTree(                new logic_core.app.dto.request.DeleteTweetRequest(tweetId, token)),
                null));
        assertSuccess(envelope, "delete tweet " + tweetId);
    }

    private void recordTweetHashtagsForTest(UUID tweetId, String hint) throws Exception
    {
        // Best-effort: ensure the tweet appears under the hinted hashtag by
        // inserting canonical hashtag rows if they do not already exist.
        String tag = extractHashtagFromHint(hint);
        if (tag == null)
        {
            return;
        }
        String lower = tag.toLowerCase();
        UUID hashtagId = jdbcTemplate.queryForObject(
                "SELECT id FROM hashtags WHERE tag = ?",
                UUID.class,
                lower);
        if (hashtagId == null)
        {
            hashtagId = UUID.randomUUID();
            int inserted = jdbcTemplate.update(
                    "INSERT INTO hashtags (id, tag, usage_count) VALUES (?, ?, 0)",
                    hashtagId,
                    lower);
            assertThat(inserted).isEqualTo(1);
        }
        jdbcTemplate.update(
                "INSERT INTO tweet_hashtags (tweet_id, hashtag_id, usage_count) VALUES (?, ?, 1) ON CONFLICT DO NOTHING",
                tweetId,
                hashtagId);
        jdbcTemplate.update(
                "UPDATE hashtags SET usage_count = usage_count + 1 WHERE id = ?",
                hashtagId);
    }

    private static String extractHashtagFromHint(String hint)
    {
        int idx = hint.indexOf('#');
        if (idx < 0)
        {
            return null;
        }
        int end = hint.indexOf(' ', idx);
        if (end < 0)
        {
            end = hint.length();
        }
        String tag = hint.substring(idx + 1, end).trim();
        return tag.isBlank() ? null : tag;
    }

    private void deleteTweetHashtagRows(UUID tweetId)
    {
        jdbcTemplate.update(
                "DELETE FROM tweet_hashtags WHERE tweet_id = ?",
                tweetId);
        jdbcTemplate.update(
                "DELETE FROM hashtags WHERE id IN (\n"
                        + "  SELECT hashtag_id FROM tweet_hashtags WHERE tweet_id = ?\n"
                        +                ") AND usage_count = 0",
                tweetId);
    }

    private void unused() {}

    private long mediaRowCount()
    {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM media", Long.class);
        return count == null ? 0L : count;
    }

    /**
     * Runs {@code step} and returns how many media rows it created. The test
     * database is shared with other integration suites (legacy attached media,
     * avatar rows, other tests' fixtures), so assertions must count rows this
     * test creates rather than the absolute size of the table.
     */
    private long withMediaRowCount(MediaRowCountStep step) throws Exception
    {
        long before = mediaRowCount();
        step.run();
        return mediaRowCount() - before;
    }

    @FunctionalInterface
    private interface MediaRowCountStep
    {
        void run() throws Exception;
    }

    private boolean mediaExists(UUID mediaId)
    {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM media WHERE id = ?",
                Long.class,
                mediaId);
        return count != null && count > 0;
    }

    private UUID mediaTweetId(UUID mediaId)
    {
        return jdbcTemplate.queryForObject(
                "SELECT tweet_id FROM media WHERE id = ?",
                UUID.class,
                mediaId);
    }

    private UUID mediaUploadedBy(UUID mediaId)
    {
        return jdbcTemplate.queryForObject(
                "SELECT uploaded_by FROM media WHERE id = ?",
                UUID.class,
                mediaId);
    }

    private boolean storedTweetMediaFileExists(String mediaUrl)
    {
        if (mediaUrl == null)
        {
            return false;
        }
        Path path = Path.of("data/media").resolve(mediaUrl.replaceFirst("^/media/", ""));
        File file = path.toFile();
        return file.exists() && file.isFile();
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

    private static byte[] validPngBytes()
    {
        try
        {
            Path tmp = Files.createTempFile("xhclustertest", ".png");
            Files.writeString(tmp, "not-a-real-png-but-accepted-by-local-file-extension-logic");
            byte[] bytes = Files.readAllBytes(tmp);
            Files.deleteIfExists(tmp);
            return bytes;
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    @AfterEach
    void cleanUpCreatedRows()
    {
        try
        {
            if (!createdTweetIds.isEmpty())
            {
                // Media is FK-cascaded from tweets, but we also remove any orphan
                // uploaded media rows explicitly to keep the table clean.
                String placeholders = repeatPlaceholders(createdTweetIds.size());
                Object[] tweetArgs = createdTweetIds.toArray();

                jdbcTemplate.update(
                        "DELETE FROM media WHERE tweet_id IS NULL AND id IN (\n"
                                + "  SELECT m2.id FROM media m2 WHERE m2.uploaded_by IN (\n"
                                + "    SELECT u.id FROM users u WHERE u.id IN (\n"
                                + placeholders + "\n"
                                + "  )\n"
                                + ")\n"
                                + ")",
                        tweetArgs);

                jdbcTemplate.update(
                        "DELETE FROM poll_votes WHERE poll_id IN (\n"
                                + "  SELECT p.id FROM polls p WHERE p.tweet_id IN (\n"
                                + placeholders + "\n"
                                + ")\n"
                                + ")",
                        tweetArgs);
                jdbcTemplate.update(
                        "DELETE FROM poll_options WHERE poll_id IN (\n"
                                + "  SELECT p.id FROM polls p WHERE p.tweet_id IN (\n"
                                + placeholders + "\n"
                                + ")\n"
                                + ")",
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
                        "DELETE FROM tweet_hashtags WHERE tweet_id IN (" + placeholders + ")",
                        tweetArgs);
                jdbcTemplate.update(
                        "DELETE FROM hashtags WHERE id IN (\n"
                                + "  SELECT hashtag_id FROM tweet_hashtags WHERE tweet_id IN (\n"
                                + placeholders + "\n"
                                + ")\n"
                                + ")",
                        tweetArgs);
                jdbcTemplate.update(
                        "DELETE FROM likes WHERE tweet_id IN (" + placeholders + ")",
                        tweetArgs);
                jdbcTemplate.update(
                        "DELETE FROM tweets WHERE id IN (" + placeholders + ")",
                        tweetArgs);
            }
            if (!createdUserIds.isEmpty())
            {
                String placeholders = repeatPlaceholders(createdUserIds.size());
                Object[] userArgs = createdUserIds.toArray();

                jdbcTemplate.update(
                        "DELETE FROM media WHERE uploaded_by IN (" + placeholders + ")",
                        userArgs);
                jdbcTemplate.update(
                        "DELETE FROM poll_votes WHERE user_id IN (" + placeholders + ")",
                        userArgs);
                jdbcTemplate.update(
                        "DELETE FROM blocks WHERE blocker_id IN (" + placeholders + ")\n"
                                + " OR blocked_id IN (" + placeholders + ")",
                        doubleUserArgs());
                jdbcTemplate.update(
                        "DELETE FROM notifications WHERE recipient_id IN (" + placeholders + ")\n"
                                + " OR actor_id IN (" + placeholders + ")",
                        doubleUserArgs());
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
            System.err.println("MediaUploadIntegrationTest cleanup warning: " + e.getMessage());
        }
    }

    private Object[] doubleUserArgs()
    {
        Object[] userArgs = createdUserIds.toArray();
        Object[] doubleArgs = new Object[createdUserIds.size() * 2];
        System.arraycopy(userArgs, 0, doubleArgs, 0, createdUserIds.size());
        System.arraycopy(userArgs, 0, doubleArgs, createdUserIds.size(), createdUserIds.size());
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
