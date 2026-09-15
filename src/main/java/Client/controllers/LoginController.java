package Client.controllers;

import Client.ClientApplicationContext;
import Client.navigation.NavigationRoute;
import Client.theme.Theme;
import Client.theme.ThemeManager;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import java.awt.Desktop;
import logic_core.app.dto.response.AuthResponse;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class LoginController
{
    private static final NavigationRoute FORGOT_PASSWORD_FXML = NavigationRoute.FORGOT_PASSWORD;
    private static final NavigationRoute REGISTER_FXML = NavigationRoute.REGISTER;
    private static final NavigationRoute HOME_FXML = NavigationRoute.HOME;

    private static final String GITHUB_URL = "https://github.com/mohammadrezaashrafian";
    private static final String DOCUMENTATION_URL = "https://github.com/mohammadrezaashrafian/x-clone";

    private static final Logger log = Logger.getLogger(LoginController.class.getName());

    @FXML
    private ImageView logoImageView;

    @FXML
    private TextField usernameField;

    @FXML
    private PasswordField passwordField;

    @FXML
    private Button loginButton;

    @FXML
    private Hyperlink signUpLink;

    @FXML
    private Hyperlink forgotPasswordLink;

    @FXML
    private Label errorLabel;

    @FXML
    private javafx.scene.layout.Pane errorContainer;
    @FXML
    private Button themeToggle;

    private final ClientApplicationContext context;
    private boolean isLoading = false;

    public LoginController(ClientApplicationContext context)
    {
        this.context = context;
    }

    @FXML
    public void initialize()
    {
        // Keep the brand logo in sync with the active theme.
        if (logoImageView != null && context.getThemeManager() != null)
        {
            context.getThemeManager().registerThemeAware(logoImageView, this::updateLogo);
        }

        // Setup keyboard handlers
        setupKeyboardHandlers();
    }

    private void updateLogo(Theme theme)
    {
        if (logoImageView == null)
        {
            return;
        }
        java.net.URL url = context.getThemeManager().brandLogo();
        if (url != null)
        {
            logoImageView.setImage(new Image(url.toExternalForm(), true));
        }
    }

    @FXML
    void handleToggleTheme(ActionEvent event)
    {
        context.getThemeManager().toggle();
    }

    private void setupKeyboardHandlers()
    {
        // Submit on Enter key
        usernameField.addEventFilter(KeyEvent.KEY_PRESSED, this::handleKeyPress);
        passwordField.addEventFilter(KeyEvent.KEY_PRESSED, this::handleKeyPress);
    }

    private void handleKeyPress(KeyEvent event)
    {
        if (event.getCode() == KeyCode.ENTER && !isLoading)
        {
            handleLogin(new ActionEvent());
            event.consume();
        }
    }

    @FXML
    void handleLogin(ActionEvent event)
    {
        if (isLoading) return;

        clearError();

        String username = usernameField.getText();
        String password = passwordField.getText();

        if (username == null || username.trim().isEmpty() || password == null || password.trim().isEmpty())
        {
            showError("Please enter both username and password.");
            return;
        }

        setLoading(true);

        context.getAuthClientService().login(username, password)
                .thenAccept(result -> Platform.runLater(() -> {
                    setLoading(false);

                    if (result.isSuccess())
                    {
                        AuthResponse auth = result.data();
                        log.info("Login OK: " + auth.username() + " / " + auth.userId());
                        context.navigation().navigate(HOME_FXML);
                    }
                    else
                    {
                        showError(result.errorMessage() != null
                                ? result.errorMessage()
                                : "Invalid username or password.");
                    }
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        setLoading(false);
                        showError("Connection error. Please try again later.");
                        log.log(Level.SEVERE, "Login flow failed", ex);
                    });
                    return null;
                });
    }

    @FXML
    void handleSignUp(ActionEvent event)
    {
        context.navigation().navigate(REGISTER_FXML);
    }

    @FXML
    void handleForgotPassword(ActionEvent event)
    {
        context.navigation().navigate(FORGOT_PASSWORD_FXML);
    }

    @FXML
    void handleOpenGitHub(ActionEvent event)
    {
        openExternalLink(GITHUB_URL);
    }

    @FXML
    void handleOpenDocumentation(ActionEvent event)
    {
        openExternalLink(DOCUMENTATION_URL);
    }

    private void openExternalLink(String url)
    {
        try
        {
            if (Desktop.isDesktopSupported())
            {
                Desktop.getDesktop().browse(new URI(url));
            }
        }
        catch (IOException | URISyntaxException e)
        {
            log.warning("Failed to open link: " + url + " - " + e.getMessage());
        }
    }

    private void setLoading(boolean loading)
    {
        isLoading = loading;

        if (loginButton != null)
        {
            loginButton.setDisable(loading);
            loginButton.setText(loading ? "Signing in..." : "Sign In");
            loginButton.getStyleClass().remove("auth-submit-btn-loading");

            if (loading)
            {
                loginButton.getStyleClass().add("auth-submit-btn-loading");
            }
        }

        if (usernameField != null)
        {
            usernameField.setDisable(loading);
        }

        if (passwordField != null)
        {
            passwordField.setDisable(loading);
        }
    }

    private void showError(String message)
    {
        if (errorLabel != null && errorContainer != null)
        {
            errorLabel.setText(message);
            errorContainer.setVisible(true);
            errorContainer.setManaged(true);
        }
        else if (errorLabel != null)
        {
            errorLabel.setText(message);
            errorLabel.setVisible(true);
        }
        else
        {
            log.warning("UI error (no errorLabel bound in FXML): " + message);
        }
    }

    private void clearError()
    {
        if (errorContainer != null)
        {
            errorContainer.setVisible(false);
            errorContainer.setManaged(false);
        }

        if (errorLabel != null)
        {
            errorLabel.setText("");
            errorLabel.setVisible(false);
        }
    }
}

