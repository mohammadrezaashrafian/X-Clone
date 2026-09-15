package Client.theme;

import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.Scene;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.Preferences;

/**
 * Central owner of the application theme state.
 *
 * <p>The active theme is one stylesheet that redefines the semantic -ds-* color
 * tokens. Switching swaps that stylesheet on every registered scene; because
 * JavaFX re-resolves looked-up colors after a stylesheet change, all mounted
 * views restyle automatically and controllers never manipulate stylesheets
 * themselves.</p>
 *
 * <p>The selected theme is persisted with {@link Preferences} (the same backing
 * store the window size uses) and restored on the next startup.</p>
 */
public final class ThemeManager
{
    private static final Logger log = Logger.getLogger(ThemeManager.class.getName());

    /** Preference node shared with MainApp's window preferences. */
    private static final String PREF_NODE = "xclone/client";
    private static final String PREF_KEY = "ui.theme";

    /** Key used to anchor a theme listener to a node's lifetime. */
    private static final String LISTENER_KEY = "themeManager.listener";

    /**
     * Shared design-system stylesheets applied to every scene, in order.
     * The active theme stylesheet is always inserted ahead of these.
     */
    private static final String[] BASE_STYLESHEETS = {
            "/Client/css/tokens.css",
            // Component layer (order is irrelevant between them, but each
            // file owns one component family).
            "/Client/css/components/buttons.css",
            "/Client/css/components/forms.css",
            "/Client/css/components/cards.css",
            "/Client/css/components/navigation.css",
            "/Client/css/components/feedback.css",
            "/Client/css/components/animations.css",
            // Structural layout, then per-view overrides.
            "/Client/css/layout.css",
            "/Client/css/views/auth.css",
            "/Client/css/views/shell.css",
            "/Client/css/views/feed.css",
            "/Client/css/views/profile.css",
            "/Client/css/views/messages.css",
            "/Client/css/views/misc.css"
    };

    private final ThemeHolder holder = new ThemeHolder(Theme.DARK);
    private final Preferences prefs;

    /** Scenes whose stylesheets are managed by this manager. */
    private final List<Scene> scenes = new ArrayList<>();

    public ThemeManager()
    {
        this(Preferences.userRoot().node(PREF_NODE));
    }

    ThemeManager(Preferences prefs)
    {
        this.prefs = prefs;
    }

    /** Current active theme. */
    public Theme current()
    {
        return holder.theme;
    }

    /** True when the light theme is active. */
    public boolean isLight()
    {
        return holder.theme == Theme.LIGHT;
    }

    /**
     * Restores the persisted theme. Call once during startup before the first
     * scene is styled.
     */
    public void restoreSavedTheme()
    {
        holder.theme = Theme.fromStorageKey(prefs.get(PREF_KEY, Theme.DARK.storageKey()));
    }

    /**
     * Starts managing the given scene: installs the full design-system
     * stylesheet list (active theme first) and re-applies it on every theme
     * change.
     */
    public void attach(Scene scene)
    {
        Objects.requireNonNull(scene, "scene");
        if (scenes.add(scene))
        {
            apply(scene);
        }
    }

    /** Stops managing a scene (e.g. a closing dialog). */
    public void detach(Scene scene)
    {
        scenes.remove(scene);
    }

    /**
     * Registers a view that keeps theme-dependent assets (logos, icons) in
     * sync. The listener is anchored to the given node: it stays alive exactly
     * as long as the node is part of a live view and is released automatically
     * afterwards, so controllers never need to unregister.
     *
     * @param anchor a node owned by the view (controls the listener lifetime)
     * @param view   callback invoked immediately and on every theme change
     */
    public void registerThemeAware(Node anchor, ThemeAware view)
    {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(view, "view");

        ChangeListener<Theme> listener = (obs, oldTheme, newTheme) -> view.onThemeChanged(newTheme);
        // Strong reference held by the node; released when the node is garbage.
        anchor.getProperties().put(LISTENER_KEY, listener);
        // Weak listener on the property detaches itself once the anchor dies.
        holder.addListener(new WeakChangeListener<>(listener));

        view.onThemeChanged(holder.theme);
    }

    /**
     * Switches the application theme, persists the choice and re-styles every
     * attached scene.
     */
    public void setTheme(Theme theme)
    {
        Objects.requireNonNull(theme, "theme");

        if (theme == holder.theme)
        {
            return;
        }

        holder.theme = theme;

        try
        {
            prefs.put(PREF_KEY, theme.storageKey());
            prefs.flush();
        }
        catch (Exception e)
        {
            log.log(Level.WARNING, "Could not persist theme preference", e);
        }

        for (Scene scene : scenes)
        {
            apply(scene);
        }

        holder.fireChanged(theme);
    }

    /** Convenience toggle between DARK and LIGHT. */
    public void toggle()
    {
        setTheme(isLight() ? Theme.DARK : Theme.LIGHT);
    }

    /**
     * Returns an unmodifiable view of the scenes currently managed by this
     * manager. Useful for debugging and for controllers that need to verify
     * that the visible scene is registered.
     */
    public List<Scene> scenes()
    {
        return List.copyOf(scenes);
    }

    /**
     * Resolves the theme-appropriate brand logo resource.
     *
     * @return URL of the logo asset for the active theme, or null if missing
     */
    public java.net.URL brandLogo()
    {
        String asset = isLight()
                ? "/Client/images/x-logo-1.png"        // dark mark on light theme
                : "/Client/images/x-logo-2.jpg";      // light mark on dark theme
        return getClass().getResource(asset);
    }

    /**
     * Replaces the previous theme stylesheet on a scene with the active one
     * and re-asserts the full design-system stylesheet order.
     */
    private void apply(Scene scene)
    {
        ObservableList<String> sheets = scene.getStylesheets();

        sheets.removeAll(BASE_STYLESHEETS);
        sheets.removeIf(s -> s.contains("/Client/css/themes/"));

        // Theme first so the shared sheets can build on its tokens.
        sheets.add(0, holder.theme.stylesheet());
        sheets.addAll(BASE_STYLESHEETS);
    }

    /**
     * Minimal observable holder for the active theme. Deliberately not a full
     * ObjectProperty: the manager exposes an imperative API and notifies
     * registered views and scenes itself.
     */
    private static final class ThemeHolder
    {
        private Theme theme;
        private final List<WeakChangeListener<Theme>> listeners = new ArrayList<>();

        ThemeHolder(Theme initial)
        {
            this.theme = initial;
        }

        void addListener(WeakChangeListener<Theme> listener)
        {
            listeners.add(listener);
        }

        void fireChanged(Theme newTheme)
        {
            listeners.removeIf(l ->
            {
                l.changed(null, null, newTheme);
                return l.wasGarbageCollected();
            });
        }
    }

    /**
     * Contract for views holding theme-dependent assets (logos, icons). The
     * controller calls {@link #registerThemeAware(Node, ThemeAware)} on mount.
     */
    @FunctionalInterface
    public interface ThemeAware
    {
        void onThemeChanged(Theme theme);
    }
}
