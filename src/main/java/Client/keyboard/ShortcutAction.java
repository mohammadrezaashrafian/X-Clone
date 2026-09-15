package Client.keyboard;

import java.util.Objects;

/**
 * A single registered keyboard shortcut.
 *
 * Combines an identifier + description with an action to execute when the
 * registered key combination is pressed anywhere in the application.
 */
public record ShortcutAction(String id, String description, Runnable action)
{
    public ShortcutAction
    {
        if (id == null || id.isBlank())
        {
            throw new IllegalArgumentException("Shortcut id must not be blank");
        }

        Objects.requireNonNull(action, "action must not be null");
    }

    public void execute()
    {
        action.run();
    }
}
