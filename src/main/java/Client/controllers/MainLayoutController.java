package Client.controllers;

import Client.ClientApplicationContext;
import Client.NavigationManager;
import Client.navigation.NavigationRoute;
import Client.theme.Theme;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import logic_core.app.dto.response.UserSearchResponse;
import logic_core.app.dto.response.UserSummaryResponse;

import java.io.IOException;
import java.net.URL;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Persistent application shell.
 *
 * The shell is a three column composition of reusable components
 * (navigation items, compose card, theme switcher, profile card, search box,
 * utility widget, footer). This controller wires those components together and
 * mounts route views into the content area.
 *
 * Route decisions, history and back navigation stay in {@link NavigationManager}
 * - this controller only renders state, exactly as before the redesign.
 */
public class MainLayoutController implements NavigationManager.ShellNavigable
{
    private static final Logger log = Logger.getLogger(MainLayoutController.class.getName());

    @FXML
    private Pane contentArea;

    @FXML
    private Button backButton;

    @FXML
    private ImageView brandLogo;

    @FXML
    private ProfileCardController profileBarController;

    // The sidebar profile card is a self-contained component: it loads its own
    // data through fx:include, so the shell needs no controller reference to it.

    @FXML
    private VBox searchResultsContainer;

    // Included component controllers -------------------------------------

    @FXML
    private NavigationItemController homeNavController;

    @FXML
    private NavigationItemController messagesNavController;

    @FXML
    private NavigationItemController profileNavController;

    @FXML
    private NavigationItemController followingNavController;

    @FXML
    private NavigationItemController followersNavController;

    @FXML
    private SearchBoxController searchInputController;

    @FXML
    private ComposeCardController composeCardController;

    @FXML
    private WhoToFollowController whoToFollowController;

    @FXML
    private DynamicWidgetController dynamicWidgetPaneController;

    private final ClientApplicationContext context;

    private NavigationRoute currentContentRoute;

    /**
     * Recipient handed over by a "Message" action just before the Messages view
     * is mounted. Consumed exactly once by MessagesController during its
     * initialization, so a stale recipient is never reused by a later mount.
     */
    private UUID pendingMessageRecipientId;

    /** Controller of the view currently mounted in the content area. */
    private Object currentContentController;

    /** Last known avatar URL of the signed-in user. */
    private String currentUserAvatarUrl;

    public MainLayoutController(ClientApplicationContext context)
    {
        this.context = context;
        context.setMainLayoutController(this);
    }

    // =========================================================
    // SHELL LIFECYCLE
    // =========================================================

    @FXML
    private void initialize()
    {
        // Assets that differ per theme (the brand mark) stay in sync through
        // the ThemeManager; the listener lifetime is anchored to the logo node.
        if (brandLogo != null && context.getThemeManager() != null)
        {
            context.getThemeManager().registerThemeAware(brandLogo, this::applyBrandLogo);
        }
    }

    /**
     * Refreshes every sidebar component that owns its own avatar loading after
     * the current user updates their profile.
     */
    public void reloadCurrentUserAvatars()
    {
        if (profileBarController != null)
        {
            profileBarController.reloadCurrentUser();
        }

        if (composeCardController != null)
        {
            composeCardController.reloadCurrentUser();
        }

        if (whoToFollowController != null)
        {
            whoToFollowController.reloadCurrentUser();
        }
    }

    /**
     * Implemented by mounted views that render tweet cards, so the shell can push
     * a changed current-user avatar into the cards already on screen instead of
     * rebuilding the whole view.
     */
    public interface LiveAvatarAware
    {
        /**
         * Re-applies the signed-in user's current avatar to the cards this view
         * has already rendered. Cards for other authors are left untouched.
         */
        void applyCurrentUserAvatar(String avatarUrl);
    }

    /**
     * Called after the signed-in user's profile changed.
     *
     * Refreshes the shell components that own avatar loading and pushes the new
     * avatar into the mounted view, so an avatar change shows up everywhere
     * immediately without the user reloading anything.
     */
    public void onCurrentUserProfileUpdated(String avatarUrl)
    {
        currentUserAvatarUrl = avatarUrl;

        reloadCurrentUserAvatars();

        if (currentUserAvatarUrl != null
                && currentContentController instanceof LiveAvatarAware avatarAware)
        {
            avatarAware.applyCurrentUserAvatar(currentUserAvatarUrl);
        }
    }

    /**
     * Refreshes the widgets that read the follow graph, so a follow/unfollow the
     * user just performed is reflected immediately instead of on next startup.
     */
    public void reloadFollowWidgets()
    {
        if (dynamicWidgetPaneController != null)
        {
            dynamicWidgetPaneController.reload();
        }
    }

    /**
     * Opens the Messages view focused on the conversation with the given user.
     * The recipient is stored first, because mounting the view (and therefore
     * creating its controller) happens asynchronously after navigation.
     */
    public void requestConversationWith(UUID recipientId)
    {
        if (recipientId == null)
        {
            return;
        }

        pendingMessageRecipientId = recipientId;

        if (context.navigation() != null)
        {
            context.navigation().navigate(NavigationRoute.MESSAGES);
        }
    }

    /**
     * Returns the recipient requested for the next Messages mount and clears it.
     */
    public UUID consumePendingMessageRecipient()
    {
        UUID recipient = pendingMessageRecipientId;

        pendingMessageRecipientId = null;

        return recipient;
    }

    @Override
    public void onShellMounted(NavigationManager manager, NavigationRoute initialRoute)
    {
        configureNavigation(manager);

        if (searchInputController != null)
        {
            searchInputController.setOnSearch(this::renderSearchResults);
        }

        // Re-attach the live scene on every shell mount so the theme toggle always
        // affects the whole application, even if the stage's scene was replaced.
        Stage hostingStage = manager.stage();
        if (hostingStage != null)
        {
            Scene hostingScene = hostingStage.getScene();
            if (hostingScene != null)
            {
                context.getThemeManager().attach(hostingScene);
            }
        }

        // Re-load the sidebar components that depend on current-user state so
        // they reflect the current session right after the shell mounts
        // (login/logout/session refresh).
        reloadCurrentUserAvatars();

        // Initial content: the route that triggered the shell mount, or HOME.
        NavigationRoute start = initialRoute != null ? initialRoute : NavigationRoute.HOME;
        currentContentRoute = start;
        manager.navigate(start);
    }

    @Override
    public void showContent(NavigationRoute route)
    {
        NavigationRoute target = route != null ? route : NavigationRoute.HOME;
        currentContentRoute = target;

        updateActiveNavState(target);
        updateBackButtonVisibility();
        loadContent(target.fxmlPath(), target);
    }

    // =========================================================
    // NAVIGATION COMPONENTS
    // =========================================================

    private void configureNavigation(NavigationManager manager)
    {
        configureItem(homeNavController, "🏠", "Home", NavigationRoute.HOME, manager);
        configureItem(messagesNavController, "📩", "Messages", NavigationRoute.MESSAGES, manager);
        configureItem(profileNavController, "👤", "Profile", NavigationRoute.PROFILE, manager);
        configureItem(followingNavController, "🤝", "Following", NavigationRoute.FOLLOWING, manager);
        configureItem(followersNavController, "👥", "Followers", NavigationRoute.FOLLOWERS, manager);
    }

    private void configureItem(
            NavigationItemController item,
            String icon,
            String label,
            NavigationRoute route,
            NavigationManager manager)
    {
        if (item == null)
        {
            return;
        }

        item.configure(icon, label, () -> manager.navigate(route));
    }

    private void updateActiveNavState(NavigationRoute route)
    {
        setActive(homeNavController, route == NavigationRoute.HOME);
        setActive(messagesNavController, route == NavigationRoute.MESSAGES);
        setActive(profileNavController, route == NavigationRoute.PROFILE);
        setActive(followingNavController, route == NavigationRoute.FOLLOWING);
        setActive(followersNavController, route == NavigationRoute.FOLLOWERS);
    }

    private void setActive(NavigationItemController item, boolean active)
    {
        if (item != null)
        {
            item.setActive(active);
        }
    }

    private void updateBackButtonVisibility()
    {
        if (backButton == null)
        {
            return;
        }

        boolean canGoBack = context.navigation() != null && context.navigation().canGoBack();
        backButton.setVisible(canGoBack);
        backButton.setManaged(canGoBack);
        backButton.setDisable(!canGoBack);
    }

    // =========================================================
    // FXML EVENT HANDLERS
    // =========================================================

    @FXML
    void handleGoBack(ActionEvent event)
    {
        if (context.navigation() != null)
        {
            context.navigation().goBack();
        }
    }

    // =========================================================
    // CONTENT LOADING (unchanged behaviour)
    // =========================================================

    private void loadContent(String fxmlPath, NavigationRoute route)
    {
        Platform.runLater(() ->
        {
            try
            {
                FXMLLoader loader = new FXMLLoader(getClass().getResource(fxmlPath));
                loader.setControllerFactory(this::createContentController);

                Parent view = loader.load();

                currentContentController = loader.getController();

                configureListController(loader, route);

                if (contentArea != null && view != null)
                {
                    contentArea.getChildren().clear();
                    contentArea.getChildren().add(view);

                    if (view instanceof Pane paneView)
                    {
                        paneView.prefWidthProperty().bind(contentArea.widthProperty());
                        paneView.prefHeightProperty().bind(contentArea.heightProperty());
                    }
                }
            }
            catch (IOException e)
            {
                log.severe("Could not load FXML view from path: " + fxmlPath + " | Error: " + e.getMessage());
            }
        });
    }

    private void configureListController(FXMLLoader loader, NavigationRoute route)
    {
        if (route != NavigationRoute.FOLLOWING && route != NavigationRoute.FOLLOWERS)
        {
            return;
        }

        Object controller = loader.getController();

        if (controller instanceof UserListController listController)
        {
            UUID currentUserId = context.getSnapshot().userId();

            if (route == NavigationRoute.FOLLOWERS)
            {
                listController.loadFollowers(currentUserId);
            }
            else
            {
                listController.loadFollowing(currentUserId);
            }
        }
    }

    private Object createContentController(Class<?> controllerClass)
    {
        if (controllerClass == TimelineController.class)
        {
            return new TimelineController(context);
        }
        else if (controllerClass == MessagesController.class)
        {
            return new MessagesController(context);
        }
        else if (controllerClass == ProfileController.class)
        {
            return new ProfileController(context);
        }
        else if (controllerClass == UserListController.class)
        {
            return new UserListController(context);
        }

        try
        {
            return controllerClass.getDeclaredConstructor().newInstance();
        }
        catch (Exception e)
        {
            throw new RuntimeException("Could not create instance of: " + controllerClass.getName(), e);
        }
    }

    // =========================================================
    // BRAND ASSETS
    // =========================================================

    private void applyBrandLogo(Theme theme)
    {
        URL logo = context.getThemeManager().brandLogo();
        Image image = logo == null ? null : new Image(logo.toExternalForm(), true);

        if (brandLogo != null)
        {
            brandLogo.setImage(image);
        }
    }

    // =========================================================
    // SEARCH (behaviour unchanged, now driven by SearchBox component)
    // =========================================================

    /**
     * Renders search results for the query submitted through the search box.
     * An empty query clears the results.
     */
    private void renderSearchResults(String query)
    {
        if (searchResultsContainer == null)
        {
            return;
        }

        if (query == null || query.isBlank())
        {
            searchResultsContainer.getChildren().clear();
            return;
        }

        context.getUserClientService()
                .searchUsers(query, 20, 0)
                .thenAccept(result -> Platform.runLater(() ->
                {
                    searchResultsContainer.getChildren().clear();

                    if (result == null || !result.isSuccess() || result.getData() == null)
                    {
                        return;
                    }

                    for (UserSearchResponse user : result.getData())
                    {
                        try
                        {
                            FXMLLoader loader = new FXMLLoader(getClass().getResource("/Client/fxml/UserItem.fxml"));
                            loader.setControllerFactory(param -> new UserItemController(context));
                            Parent node = loader.load();

                            UserItemController controller = loader.getController();
                            controller.setUser(
                                    UserSummaryResponse.builder()
                                            .userId(user.id())
                                            .username(user.username())
                                            .displayName(user.displayName())
                                            .avatarUrl(user.avatarUrl())
                                            .verified(false)
                                            .build()
                            );

                            searchResultsContainer.getChildren().add(node);
                        }
                        catch (IOException e)
                        {
                            log.warning("Failed to render search result: " + e.getMessage());
                        }
                    }
                }));
    }
}
