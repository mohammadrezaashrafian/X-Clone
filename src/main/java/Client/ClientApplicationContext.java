package Client;

import Client.Service.*;
import Client.config.ServerConfig;
import Client.controllers.MainLayoutController;
import Client.session.ClientSession;
import Client.theme.ThemeManager;
import Client.transport.SocketClient;

import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Getter
public final class ClientApplicationContext implements AutoCloseable
{
    private static final Logger log = LoggerFactory.getLogger(ClientApplicationContext.class);

    private final ClientSession session;
    private final SocketClient socketClient;
    private final ExecutorService networkExecutor;
    @Setter private NavigationManager navigationManager;
    @Getter private final ThemeManager themeManager;
    @Setter private MainLayoutController mainLayoutController;
    @Getter private final TimelineClientService timelineService;
    @Getter private final BookmarkClientService bookmarkClientService;
    @Getter private final RelationClientService relationClientService;
    @Getter private final AuthClientService authClientService;
    @Getter private final ConversationClientService conversationClientService;
    @Getter private final TweetClientService tweetService;
    @Getter private final UserClientService userClientService;
    @Getter private final MessageClientService messageClientService;
    @Getter private final FollowQueryClientService followQueryClientService;
    @Getter private final MediaClientService mediaClientService;

    private final AtomicBoolean closed = new AtomicBoolean(false);

    public ClientApplicationContext(ServerConfig config)
    {
        this.session = new ClientSession();
        this.socketClient = new SocketClient(config, session);
        this.networkExecutor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "client-network");
            t.setDaemon(true);
            return t;
        });

        this.themeManager = new ThemeManager();
        this.timelineService = new TimelineClientService(this);
        this.bookmarkClientService = new BookmarkClientService(this);
        this.tweetService = new TweetClientService(this);
        this.relationClientService = new RelationClientService(this);
        this.authClientService = new AuthClientService(this);
        this.conversationClientService = new ConversationClientService(this);
        this.userClientService = new UserClientService(this);
        this.messageClientService = new MessageClientService(this);
        this.followQueryClientService = new FollowQueryClientService(this);
        this.mediaClientService = new MediaClientService(this);
    }

    public ClientSession session()
    {
        return session;
    }

    public SocketClient socketClient()
    {
        return socketClient;
    }

    public ExecutorService networkExecutor()
    {
        return networkExecutor;
    }

    public NavigationManager navigation()
    {
        return navigationManager;
    }


    public ClientSession.SessionSnapshot getSnapshot()
    {
        return session.snapshot();
    }

    @Override
    public void close()
    {
        if (!closed.compareAndSet(false, true))
        {
            return;
        }

        networkExecutor.shutdown();

        try
        {
            socketClient.close();
        }
        catch (Exception e)
        {
            log.warn("Error while closing SocketClient", e);
        }

        try
        {
            if (!networkExecutor.awaitTermination(2, TimeUnit.SECONDS))
            {
                networkExecutor.shutdownNow();
                if (!networkExecutor.awaitTermination(1, TimeUnit.SECONDS))
                {
                    log.warn("networkExecutor did not terminate");
                }
            }
        }
        catch (InterruptedException e)
        {
            networkExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        try
        {
            session.clear();
        }
        catch (Exception e)
        {
            log.warn("Error while clearing session", e);
        }
    }
}
