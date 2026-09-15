package Client.controllers;

import Client.ClientApplicationContext;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;

/**
 * Reusable sidebar navigation item (icon + label).
 *
 * The item owns no routing knowledge: the host configures the icon, the text
 * and the action, and drives the active state from the current route. This
 * keeps every navigation entry visually identical by construction.
 */
public class NavigationItemController
{
    private static final String ACTIVE_CLASS = "nav-item-active";

    @FXML
    private Button navButton;

    @FXML
    private Label navIcon;

    @FXML
    private Label navLabel;

    private final ClientApplicationContext context;

    private Runnable action;

    public NavigationItemController(ClientApplicationContext context)
    {
        this.context = context;
    }

    @FXML
    private void initialize()
    {
        navButton.setOnAction(event ->
        {
            if (action != null)
            {
                action.run();
            }
        });
    }

    /**
     * Sets the icon glyph, the visible label and the action to run on click.
     */
    public void configure(String icon, String text, Runnable action)
    {
        navIcon.setText(icon == null ? "" : icon);
        navLabel.setText(text == null ? "" : text);
        navButton.setAccessibleText(text == null ? "" : text);
        this.action = action;
    }

    /** Applies or clears the active-route styling. */
    public void setActive(boolean active)
    {
        navButton.getStyleClass().remove(ACTIVE_CLASS);

        if (active)
        {
            navButton.getStyleClass().add(ACTIVE_CLASS);
        }
    }

    /** The button node, exposed for hosts that need to add badges etc. */
    public Button button()
    {
        return navButton;
    }
}
