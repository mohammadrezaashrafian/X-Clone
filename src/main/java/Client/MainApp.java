package Client;

import Client.config.ServerConfig;
import Client.keyboard.ShortcutAction;
import Client.keyboard.ShortcutManager;
import Client.navigation.NavigationRoute;
import Client.theme.ThemeManager;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;

import java.util.prefs.Preferences;

public class MainApp extends Application
{
    private static final String PREF_NODE = "xclone/client";
    private static final String PREF_WIDTH = "window.width";
    private static final String PREF_HEIGHT = "window.height";
    private static final String PREF_X = "window.x";
    private static final String PREF_Y = "window.y";

    private static final double DEFAULT_WIDTH = 1100;
    private static final double DEFAULT_HEIGHT = 700;
    private static final double MIN_WIDTH = 900;
    private static final double MIN_HEIGHT = 600;

    private ClientApplicationContext context;
    private Stage primaryStage;

    @Override
    public void start(Stage primaryStage)
    {
        this.primaryStage = primaryStage;

        ServerConfig config = ServerConfig.defaultLocal();
        this.context = new ClientApplicationContext(config);
        NavigationManager navigationManager = new NavigationManager(primaryStage, context);
        context.setNavigationManager(navigationManager);

        restoreWindowPreferences(primaryStage);
        primaryStage.setMinWidth(MIN_WIDTH);
        primaryStage.setMinHeight(MIN_HEIGHT);
        primaryStage.setOnCloseRequest(event -> persistWindowPreferences());

        // Restore the persisted theme before any scene is styled, then let the
        // ThemeManager own the design-system stylesheets on the primary scene.
        ThemeManager themeManager = context.getThemeManager();
        themeManager.restoreSavedTheme();

        // Apply shared design-system stylesheets to every loaded scene.
        Scene scene = primaryStage.getScene();
        if (scene == null)
        {
            primaryStage.setScene(new Scene(new Pane()));
            scene = primaryStage.getScene();
        }
        themeManager.attach(scene);

        installGlobalShortcuts(scene);

        navigationManager.navigate(NavigationRoute.LOGIN);
    }

    /**
     * Global keyboard shortcuts (infrastructure only - actions are placeholders
     * until the compose/search features are implemented in later phases).
     */
    private void installGlobalShortcuts(Scene scene)
    {
        ShortcutManager shortcuts = new ShortcutManager();
        shortcuts.register(
                new KeyCodeCombination(KeyCode.N, KeyCombination.CONTROL_DOWN),
                new ShortcutAction("compose", "Open tweet composer", () ->
                {
                    // Placeholder - compose feature arrives in a later phase.
                })
        );

        shortcuts.register(
                new KeyCodeCombination(KeyCode.K, KeyCombination.CONTROL_DOWN),
                new ShortcutAction("search", "Open quick search", () ->
                {
                    // Placeholder - quick search arrives in a later phase.
                })
        );

        shortcuts.install(scene);
    }

    private void restoreWindowPreferences(Stage stage)
    {
        try
        {
            Preferences prefs = Preferences.userRoot().node(PREF_NODE);

            double width = prefs.getDouble(PREF_WIDTH, DEFAULT_WIDTH);
            double height = prefs.getDouble(PREF_HEIGHT, DEFAULT_HEIGHT);

            stage.setWidth(width);
            stage.setHeight(height);

            if (prefs.getBoolean("window.positioned", false))
            {
                stage.setX(prefs.getDouble(PREF_X, 0));
                stage.setY(prefs.getDouble(PREF_Y, 0));
            }
        }
        catch (Exception e)
        {
            System.err.println("Could not restore window preferences: " + e.getMessage());
        }
    }

    private void persistWindowPreferences()
    {
        if (primaryStage == null)
        {
            return;
        }

        try
        {
            Preferences prefs = Preferences.userRoot().node(PREF_NODE);

            prefs.putDouble(PREF_WIDTH, primaryStage.getWidth());
            prefs.putDouble(PREF_HEIGHT, primaryStage.getHeight());
            prefs.putDouble(PREF_X, primaryStage.getX());
            prefs.putDouble(PREF_Y, primaryStage.getY());
            prefs.putBoolean("window.positioned", true);
            prefs.flush();
        }
        catch (Exception e)
        {
            System.err.println("Could not save window preferences: " + e.getMessage());
        }
    }


    @Override
    public void stop()
    {
        persistWindowPreferences();
        safeClose();
    }

    private void safeClose()
    {
        if (context == null)
        {
            return;
        }
        try
        {
            context.close();
        }
        catch (Exception e)
        {
            System.err.println("Error while closing ClientApplicationContext: " + e.getMessage());
        }
    }

    public static void main(String[] eloquence)
    {
        launch(eloquence);
    }
}