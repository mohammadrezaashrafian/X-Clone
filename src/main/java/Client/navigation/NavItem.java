package Client.navigation;

import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.scene.control.Button;

/**
 * Reusable sidebar navigation item.
 *
 * Encapsulates the route, the label, and the active-state CSS handling so
 * that no per-button logic is duplicated in the shell controller.
 */
public final class NavItem
{
    private final Button button;
    private final NavigationRoute route;

    public NavItem(NavigationRoute route, String label, EventHandler<ActionEvent> handler)
    {
        this.route = route;
        this.button = new Button(label);
        this.button.getStyleClass().add("sidebar-button");
        this.button.setOnAction(handler);
        this.button.setMaxWidth(Double.MAX_VALUE);
    }

    public Button button()
    {
        return button;
    }

    public NavigationRoute route()
    {
        return route;
    }

    /**
     * Marks this item as the active destination. Styling is handled entirely
     * by the CSS class - no inline styles are used.
     */
    public void setActive(boolean active)
    {
        button.getStyleClass().remove("sidebar-button-active");

        if (active)
        {
            button.getStyleClass().add("sidebar-button-active");
        }
    }

    public boolean isActive()
    {
        return button.getStyleClass().contains("sidebar-button-active");
    }

    /**
     * Updates the badge count displayed next to the label. Passing null or
     * a non-positive value hides the badge.
     */
    public void setBadge(Integer count)
    {
        button.setUserData(count);

        // Badge rendering is delegated to the shell once the Badge component
        // is wired into the layout. For now the count is stored on the item
        // so future notification integration can consume it without changes
        // to the navigation contract.
    }

    public Integer getBadge()
    {
        Object data = button.getUserData();

        return data instanceof Integer count ? count : null;
    }
}
