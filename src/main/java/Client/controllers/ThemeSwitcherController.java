package Client.controllers;

import Client.ClientApplicationContext;
import Client.theme.Theme;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.layout.HBox;

/**
 * Reusable segmented theme toggle (moon / sun).
 *
 * The component only talks to the {@code ThemeManager}: it never touches
 * stylesheets itself. The active segment is kept in sync through
 * {@code registerThemeAware}, so the state stays correct no matter where the
 * theme was changed from.
 */
public class ThemeSwitcherController
{
    private static final String ACTIVE_CLASS = "theme-segment-active";

    @FXML
    private HBox themeSwitcher;

    @FXML
    private Button darkSegment;

    @FXML
    private Button lightSegment;

    private final ClientApplicationContext context;

    public ThemeSwitcherController(ClientApplicationContext context)
    {
        this.context = context;
    }

    @FXML
    private void initialize()
    {
        darkSegment.setOnAction(event -> applyThemeFromToggle(Theme.DARK));
        lightSegment.setOnAction(event -> applyThemeFromToggle(Theme.LIGHT));

        // Anchor the listener to the widget so it is released with the view.
        context.getThemeManager().registerThemeAware(themeSwitcher, this::applyTheme);
    }

    private void applyThemeFromToggle(Theme theme)
    {
        // Update the segment immediately so the toggle always reflects the chosen theme
        // on the very first click, then let ThemeManager propagate the change to all
        // attached scenes and notify listeners.
        applyTheme(theme);
        context.getThemeManager().setTheme(theme);
    }

    private void applyTheme(Theme theme)
    {
        setActive(darkSegment, theme == Theme.DARK);
        setActive(lightSegment, theme == Theme.LIGHT);
    }

    private void setActive(Button segment, boolean active)
    {
        if (segment == null)
        {
            return;
        }

        segment.getStyleClass().remove(ACTIVE_CLASS);

        if (active)
        {
            segment.getStyleClass().add(ACTIVE_CLASS);
        }
    }
}
