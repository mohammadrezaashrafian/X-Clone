package Client.controllers;

import Client.ClientApplicationContext;
import javafx.fxml.FXML;
import javafx.scene.control.Hyperlink;

import java.awt.Desktop;
import java.net.URI;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reusable footer for the utility panel.
 *
 * Uses the same {@link Desktop}-based link handling as the auth footer, so
 * external links behave identically across the application. Version text is
 * static (supplied by the FXML) and mirrors the auth footer.
 */
public class FooterController
{
    private static final Logger log = Logger.getLogger(FooterController.class.getName());

    private static final String TERMS_URL = "https://github.com/mohammadrezaashrafian/x-clone";
    private static final String PRIVACY_URL = "https://github.com/mohammadrezaashrafian/x-clone";
    private static final String COOKIES_URL = "https://github.com/mohammadrezaashrafian/x-clone";
    private static final String ACCESSIBILITY_URL = "https://github.com/mohammadrezaashrafian/x-clone";

    @FXML
    private Hyperlink termsLink;

    @FXML
    private Hyperlink privacyLink;

    @FXML
    private Hyperlink cookiesLink;

    @FXML
    private Hyperlink accessibilityLink;

    private final ClientApplicationContext context;

    public FooterController(ClientApplicationContext context)
    {
        this.context = context;
    }

    @FXML
    private void initialize()
    {
        wire(termsLink, TERMS_URL);
        wire(privacyLink, PRIVACY_URL);
        wire(cookiesLink, COOKIES_URL);
        wire(accessibilityLink, ACCESSIBILITY_URL);
    }

    private void wire(Hyperlink link, String url)
    {
        if (link == null)
        {
            return;
        }

        link.setOnAction(event -> open(url));
    }

    private void open(String url)
    {
        if (!Desktop.isDesktopSupported())
        {
            log.warning("Desktop browsing is not supported on this platform: " + url);
            return;
        }

        try
        {
            Desktop.getDesktop().browse(new URI(url));
        }
        catch (Exception e)
        {
            log.log(Level.WARNING, "Could not open link: " + url, e);
        }
    }
}
