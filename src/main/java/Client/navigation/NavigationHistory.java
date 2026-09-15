package Client.navigation;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * Stores previously visited routes to support back navigation.
 *
 * The history only records shell-level routes. Authentication transitions
 * (e.g. LOGIN -> REGISTER) intentionally bypass history so that back
 * navigation never leaves the logged-in application into an auth screen.
 */
public final class NavigationHistory
{
    private final Deque<NavigationRoute> history = new ArrayDeque<>();

    private static final int MAX_DEPTH = 50;

    /**
     * Pushes a route onto the history stack.
     *
     * @param route the route being left; ignored when null or non-top-level
     */
    public void push(NavigationRoute route)
    {
        if (route == null || !route.isTopLevel())
        {
            return;
        }

        // Avoid duplicating consecutive identical entries.
        if (!history.isEmpty() && history.peek() == route)
        {
            return;
        }

        history.push(route);

        while (history.size() > MAX_DEPTH)
        {
            history.removeLast();
        }
    }

    /**
     * Pops the most recent route from the stack.
     *
     * @return the previous route, or null when history is empty
     */
    public NavigationRoute pop()
    {
        return history.pollFirst();
    }

    /**
     * @return true when at least one previous route exists
     */
    public boolean canGoBack()
    {
        return !history.isEmpty();
    }

    /**
     * Clears all recorded history (e.g. on logout).
     */
    public void clear()
    {
        history.clear();
    }

    public int size()
    {
        return history.size();
    }

    public void validateState()
    {
        Objects.requireNonNull(history, "history must not be null");
    }
}
