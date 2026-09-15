package Client.keyboard;

import javafx.scene.input.KeyCombination;
import javafx.scene.Scene;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Application-level keyboard shortcut infrastructure.
 *
 * Registers {@link KeyCombination} -> {@link ShortcutAction} mappings and
 * installs a single scene-level key listener. Controllers never attach their
 * own global listeners; they register named actions with this manager.
 */
public final class ShortcutManager
{
    private static final Logger log = Logger.getLogger(ShortcutManager.class.getName());

    private final Map<KeyCombination, ShortcutAction> shortcuts = new LinkedHashMap<>();
    private boolean installed;

    /**
     * Registers a shortcut.
     */
    public void register(KeyCombination combination, ShortcutAction action)
    {
        shortcuts.put(combination, action);
    }

    /**
     * Removes a previously registered shortcut.
     */
    public void unregister(KeyCombination combination)
    {
        shortcuts.remove(combination);
    }

    /**
     * Installs the global key listener on the given scene. Re-installing on a
     * new scene is safe; the previous scene listener is replaced implicitly
     * when the old scene is discarded by navigation.
     */
    public void install(Scene scene)
    {
        if (scene == null || installed)
        {
            return;
        }

        scene.setOnKeyPressed(event ->
        {
            for (Map.Entry<KeyCombination, ShortcutAction> entry : shortcuts.entrySet())
            {
                if (entry.getKey().match(event))
                {
                    event.consume();

                    try
                    {
                        entry.getValue().execute();
                    }
                    catch (RuntimeException ex)
                    {
                        log.warning("Shortcut '" + entry.getValue().id() + "' failed: " + ex.getMessage());
                    }

                    return;
                }
            }
        });

        installed = true;
    }

    public int shortcutCount()
    {
        return shortcuts.size();
    }
}
