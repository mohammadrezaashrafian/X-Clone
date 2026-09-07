package Testing;

import Client.ClientApplicationContext;
import Client.Service.AuthClientService;
import Client.Service.TimelineClientService;
import Client.Service.TweetClientService;
import Client.config.ServerConfig;
import logic_core.app.dto.request.GetTimelineResponse;
import logic_core.app.dto.response.AuthResponse;
import logic_core.app.dto.response.TweetResponse;
import logic_core.app.dto.timeline.TimelineTweet;
import logic_core.common.result.Result;
import logic_core.domain.repository.TimelineType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * LIVE smoke test: proves the real JavaFX <b>client application bootstrap</b> —
 * {@link ClientApplicationContext} (which initializes the SQLite client cache
 * and all client services) plus the desktop Login→Home journey — against a
 * running migrated backend through the <b>default</b> client configuration
 * ({@link ServerConfig#defaultLocal()} → port {@value ServerConfig#DEFAULT_PORT},
 * matching the backend {@code SocketServer} {@code ${server.socket.port:9090}}
 * default).
 *
 * <p>Walked journey: register → session token → login on a fresh context →
 * HOME timeline (the exact {@code AuthClientService}/{@code TimelineClientService}
 * calls the desktop UI makes).
 *
 * <p>The test is skipped (assumption) when no backend listens on the default
 * port, so the suite stays green without a live server. Rows created on the
 * live backend are deleted afterwards via direct JDBC cleanup, and the local
 * SQLite cache file created by the client bootstrap is removed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ClientServerLiveSmokeTest {

    private static boolean serverAvailable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", ServerConfig.DEFAULT_PORT), 1500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private final List<UUID> createdUserIds = new ArrayList<>();
    private final List<UUID> createdTweetIds = new ArrayList<>();

    private JdbcTemplate liveDb() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl("jdbc:postgresql://localhost:5432/xclonedb");
        dataSource.setUsername("postgres");
        dataSource.setPassword("FinalProject_Dev");
        return new JdbcTemplate(dataSource);
    }

    @AfterEach
    void cleanUpCreatedRows() {
        try {
            JdbcTemplate jdbc = liveDb();

            if (!createdTweetIds.isEmpty()) {
                StringBuilder tweetPlaceholders = new StringBuilder();
                for (int i = 0; i < createdTweetIds.size(); i++) {
                    if (i > 0) {
                        tweetPlaceholders.append(",");
                    }
                    tweetPlaceholders.append("?");
                }
                jdbc.update("DELETE FROM tweets WHERE id IN (" + tweetPlaceholders + ")",
                        createdTweetIds.toArray());
            }

            if (!createdUserIds.isEmpty()) {
                StringBuilder placeholders = new StringBuilder();
                for (int i = 0; i < createdUserIds.size(); i++) {
                    if (i > 0) {
                        placeholders.append(",");
                    }
                    placeholders.append("?");
                }
                Object[] userArgs = createdUserIds.toArray();
                jdbc.update("DELETE FROM sessions WHERE user_id IN (" + placeholders + ")", userArgs);
                jdbc.update("DELETE FROM users WHERE id IN (" + placeholders + ")", userArgs);
            }
        } catch (Exception e) {
            System.err.println("ClientServerLiveSmokeTest cleanup warning: " + e.getMessage());
        }
    }

    @AfterAll
    void removeLocalCacheArtifact() {
        for (String suffix : new String[]{"", "-wal", "-shm"}) {
            try {
                java.nio.file.Files.deleteIfExists(
                        java.nio.file.Path.of("client-cache.db" + suffix));
            } catch (Exception ignored) {
            }
        }
    }

    @Test
    void desktopClientBootstrapAndJourney_registerLoginHome_againstLiveBackend() throws Exception {
        assumeTrue(serverAvailable(),
                "No backend listening on localhost:" + ServerConfig.DEFAULT_PORT
                        + " — live smoke skipped (start the Spring Boot server first)");

        ServerConfig config = ServerConfig.defaultLocal();
        assertEquals(ServerConfig.DEFAULT_PORT, config.port(),
                "default client config must target the SocketServer port");

        String username = "smoke_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String email = username + "@smoketest.com";
        String password = "SmokePass123!";

        // ---------------------------------------------------------------
        // 1. Full client bootstrap: ClientApplicationContext initializes the
        //    SQLite client cache (ClientCacheDatabase) and all client services.
        // ---------------------------------------------------------------
        try (ClientApplicationContext context =
                     new ClientApplicationContext(config)) {

            AuthClientService auth = new AuthClientService(context);

            // 2. Register (real client service over SocketClient)
            AuthClientService.AuthResult<AuthResponse> register =
                    auth.register(username, email, password, "Smoke User")
                            .get(10, TimeUnit.SECONDS);
            assertTrue(register.isSuccess(),
                    "register must succeed — code=" + register.errorCode()
                            + ", message=" + register.errorMessage());
            assertNotNull(register.data().token(), "register must issue a session token");
            createdUserIds.add(register.data().userId());
            assertTrue(context.session().isLoggedIn(), "client session must be logged in");
            assertEquals(register.data().token(), context.session().getToken());

            // 3. Login with a fresh context (fresh TCP connection + fresh cache EMF)
            try (ClientApplicationContext loginContext =
                         new ClientApplicationContext(config)) {

                AuthClientService.AuthResult<AuthResponse> login =
                        new AuthClientService(loginContext)
                                .login(username, password)
                                .get(10, TimeUnit.SECONDS);
                assertTrue(login.isSuccess(),
                        "login must succeed — code=" + login.errorCode()
                                + ", message=" + login.errorMessage());
                assertNotNull(login.data().token());
                createdUserIds.add(login.data().userId());
                assertEquals(login.data().token(), loginContext.session().getToken());

                // 4. HOME timeline through the real client service
                TimelineClientService timeline = new TimelineClientService(loginContext);
                Result<GetTimelineResponse> home =
                        timeline.getTimeline(
                                TimelineType.HOME,
                                loginContext.session().getCurrentUserId(),
                                null,
                                0,
                                20
                        ).get(10, TimeUnit.SECONDS);
                assertTrue(home.isSuccess(),
                        "HOME timeline must succeed — error=" + home.getError());
                assertNotNull(home.getData(), "HOME timeline response must be present");
                assertNotNull(home.getData().tweets(), "HOME timeline tweets must parse to a list");

                // 5. Create a tweet and retrieve it via TWEET_GET (TweetClientService.getTweet)
                TweetClientService tweets = new TweetClientService(loginContext);
                Result<TweetResponse> created =
                        tweets.createTweet(
                                "single-tweet retrieval smoke " + UUID.randomUUID(),
                                null,
                                null,
                                null,
                                null
                        ).get(10, TimeUnit.SECONDS);
                assertTrue(created.isSuccess(),
                        "createTweet must succeed — error=" + created.getError());
                assertNotNull(created.getData(), "createTweet must return a TweetResponse");
                createdTweetIds.add(created.getData().id());

                Result<TimelineTweet> single =
                        tweets.getTweet(created.getData().id())
                                .get(10, TimeUnit.SECONDS);
                assertTrue(single.isSuccess(),
                        "getTweet must succeed — error=" + single.getError());
                assertNotNull(single.getData(), "getTweet must return a TimelineTweet");
                assertEquals(created.getData().id(), single.getData().tweetId(),
                        "getTweet must return the requested tweet id");
            }
        }
    }
}
