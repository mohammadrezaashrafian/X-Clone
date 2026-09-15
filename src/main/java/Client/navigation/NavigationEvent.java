package Client.navigation;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Notifies listeners when the active application route changes.
 *
 * Listeners are typically shell components (e.g. the sidebar) that need to
 * update their visual state when navigation occurs.
 */
public final class NavigationEvent
{
    public interface RouteListener
    {
        void onRouteChanged(NavigationRoute route);
    }

    private final List<RouteListener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(RouteListener listener)
    {
        if (listener != null)
        {
            listeners.add(listener);
        }
    }

    public void removeListener(RouteListener listener)
    {
        listeners.remove(listener);
    }

    public void fireRouteChanged(NavigationRoute route)
    {
        for (RouteListener listener : listeners)
        {
            try
            {
                listener.onRouteChanged(route);
            }
            catch (RuntimeException ignored)
            {
                // A broken listener must never break navigation.
            }
        }
    }
}
