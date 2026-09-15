package Client;

import Client.navigation.NavigationEvent;
import Client.navigation.NavigationHistory;
import Client.navigation.NavigationRoute;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.Objects;

/**
 * Unified application navigation.
 *
 * Two navigation modes:
 * 1. Shell navigation - top-level routes rendered inside the persistent
 *    MainLayout content area, with history and back navigation.
 * 2. Auth navigation - full scene-root replacement for login/register flows
 *    which do not participate in shell history.
 */
public final class NavigationManager
{
    private final Stage stage;
    private final ClientApplicationContext context;
    private final NavigationHistory history = new NavigationHistory();
    private final NavigationEvent events = new NavigationEvent();

    private NavigationRoute currentRoute;
    private ShellNavigable shellController;

    public NavigationManager(Stage stage, ClientApplicationContext context)
    {
        this.stage = Objects.requireNonNull(stage, "stage must not be null");
        this.context = Objects.requireNonNull(context, "context must not be null");
    }

    /**
     * Navigates to a route. Top-level routes delegate to the shell controller
     * (keeping the persistent shell mounted); auth routes replace the scene root.
     */
    public void navigate(NavigationRoute route)
    {
        Objects.requireNonNull(route, "route must not be null");

        if (route.isTopLevel())
        {
            navigateShell(route);
        }
        else
        {
            navigateAuth(route);
        }
    }

    private void navigateShell(NavigationRoute route)
    {
        if (shellController == null)
        {
            // Shell not mounted yet - replace scene root with MainLayout and
            // instruct it to open the requested content route.
            history.clear();
            currentRoute = route;
            loadSceneRoot("/Client/fxml/MainLayout.fxml");
            return;
        }

        if (route == currentRoute)
        {
            // Same route: re-render content without polluting history.
            notifyRouteChanged();
            return;
        }

        history.push(currentRoute);
        currentRoute = route;
        notifyRouteChanged();
        updateStageTitle();
    }

    private void navigateAuth(NavigationRoute route)
    {
        currentRoute = route;
        shellController = null;
        loadSceneRoot(route.fxmlPath());
        updateStageTitle();
    }

    /**
     * Loads an FXML as the full scene root and wires shell controllers.
     */
    private void loadSceneRoot(String fxmlPath)
    {
        try
        {
            FXMLLoader loader = new FXMLLoader(getClass().getResource(fxmlPath));

            if (loader.getLocation() == null)
            {
                throw new IllegalArgumentException("FXML not found on classpath: " + fxmlPath);
            }

            loader.setControllerFactory(this::createController);
            Parent root = loader.load();

            Object controller = loader.getController();

            if (controller instanceof ShellNavigable shell)
            {
                shellController = shell;
                history.clear();
                shell.onShellMounted(this, currentRoute);
                events.fireRouteChanged(currentRoute);
            }
            else
            {
                shellController = null;
            }

            Scene scene = stage.getScene();

            if (scene == null)
            {
                Scene newScene = new Scene(root);
                stage.setScene(newScene);
                context.getThemeManager().attach(newScene);
            }
            else
            {
                scene.setRoot(root);
            }

            if (!stage.isShowing())
            {
                stage.show();
            }
        }
        catch (IOException e)
        {
            throw new IllegalStateException("Failed to load FXML: " + fxmlPath, e);
        }
    }

    private void updateStageTitle()
    {
        String title = currentRoute != null ? currentRoute.title() : null;

        if (title != null && !title.isBlank())
        {
            stage.setTitle(title);
        }
    }

    /**
     * Instructs the mounted shell to display a content route.
     */
    private void notifyRouteChanged()
    {
        if (shellController != null)
        {
            shellController.showContent(currentRoute);
        }

        events.fireRouteChanged(currentRoute);
    }

    /**
     * Navigates back when history exists. No-op otherwise.
     *
     * @return true when a back navigation occurred
     */
    public boolean goBack()
    {
        if (!history.canGoBack())
        {
            return false;
        }

        NavigationRoute previous = history.pop();
        currentRoute = previous;

        if (shellController != null)
        {
            shellController.showContent(previous);
        }

        events.fireRouteChanged(previous);
        updateStageTitle();
        return true;
    }

    public boolean canGoBack()
    {
        return history.canGoBack();
    }

    public NavigationRoute currentRoute()
    {
        return currentRoute;
    }

    public void addListener(NavigationEvent.RouteListener listener)
    {
        events.addListener(listener);
    }

    public void removeListener(NavigationEvent.RouteListener listener)
    {
        events.removeListener(listener);
    }

    /**
     * Clears shell state (e.g. on logout).
     */
    public void reset()
    {
        history.clear();
        shellController = null;
        currentRoute = null;
    }

    /**
     * Implemented by the application shell controller so that the
     * NavigationManager can mount it and delegate content switching.
     */
    public interface ShellNavigable
    {
        /**
         * Called once after the shell FXML has been loaded.
         */
        void onShellMounted(NavigationManager manager, NavigationRoute initialRoute);

        /**
         * Called on every shell-level route change.
         */
        void showContent(NavigationRoute route);
    }

    private Object createController(Class<?> type)
    {
        try
        {
            Constructor<?> withContext = type.getConstructor(ClientApplicationContext.class);
            return withContext.newInstance(context);
        }
        catch (NoSuchMethodException ignored)
        {
            // fallback
        }
        catch (ReflectiveOperationException e)
        {
            throw new IllegalStateException(
                    "Failed to create controller with context: " + type.getName(), e);
        }

        try
        {
            Constructor<?> noArg = type.getDeclaredConstructor();
            noArg.setAccessible(true);
            return noArg.newInstance();
        }
        catch (ReflectiveOperationException e)
        {
            throw new IllegalStateException(
                    "Controller must have either "
                            + type.getSimpleName() + "(ClientApplicationContext) "
                            + "or a no-arg constructor: " + type.getName(),
                    e);
        }
    }

    public Stage stage()
    {
        return stage;
    }

    public ClientApplicationContext context()
    {
        return context;
    }
}