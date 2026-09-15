package Client.controllers;

import Client.ClientApplicationContext;
import Client.PasswordResetContext;
import Client.Service.AuthClientService;
import Client.theme.Theme;
import Client.theme.ThemeManager;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import logic_core.app.dto.response.AuthResponse;

import java.awt.*;
import java.net.URI;
import java.util.logging.Level;
import java.util.logging.Logger;


public class RegisterController
{
    private static final Logger log = Logger.getLogger(RegisterController.class.getName());

    private static final Client.navigation.NavigationRoute VERIFY_FXML = Client.navigation.NavigationRoute.VERIFY_CODE;
    private static final Client.navigation.NavigationRoute LOGIN_FXML = Client.navigation.NavigationRoute.LOGIN;

    @FXML
    private TextField usernameField;

    @FXML
    private TextField emailField;

    @FXML
    private PasswordField passwordField;

    @FXML
    private PasswordField confirmPasswordField;

    @FXML
    private Button signUpButton;

    @FXML
    private TextField displayNameField;

    @FXML
    private Label errorLabel;

    @FXML
    private ImageView logoImageView;

    private final ClientApplicationContext context;

    public RegisterController(ClientApplicationContext context)
    {
        this.context = context;
    }

    @FXML
    private void initialize()
    {
        if (logoImageView != null && context.getThemeManager() != null)
        {
            context.getThemeManager().registerThemeAware(logoImageView, theme ->
            {
                java.net.URL url = context.getThemeManager().brandLogo();
                if (url != null)
                {
                    logoImageView.setImage(new javafx.scene.image.Image(url.toExternalForm(), true));
                }
            });
        }
    }

    @FXML
    void handleRegister(ActionEvent event)
    {
        hideError();

        String displayName = displayNameField.getText();
        String username = usernameField.getText();
        String email = emailField.getText();
        String password = passwordField.getText();
        String confirmPassword = confirmPasswordField.getText();

        if (displayName.isEmpty() || username.isEmpty() || email.isEmpty() || password.isEmpty() || confirmPassword.isEmpty())
        {
            showError("Please fill in all required fields.");
            return;
        }

        if (!password.equals(confirmPassword))
        {
            showError("Passwords do not match.");
            return;
        }

        setLoading(true);


        context.getAuthClientService().register(username, email, password, displayName)
                .thenAccept(result -> Platform.runLater(() -> {
                    setLoading(false);

                    if (result.isSuccess())
                    {
                        AuthResponse auth = result.data();
                        log.info("Register OK: " + auth.username() + " / " + auth.userId());

                        // Navigate to email verification screen.
                        PasswordResetContext.getInstance().setEmail(email);
                        PasswordResetContext.getInstance()
                                .setVerificationMode(PasswordResetContext.VerificationMode.REGISTRATION);
                        PasswordResetContext.getInstance().setCode(null);
                        context.navigation().navigate(VERIFY_FXML);
                    }
                    else
                    {
                        showError(result.errorMessage() != null
                                ? result.errorMessage()
                                : "Registration failed");
                    }
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        setLoading(false);

                        showError("Connection error. Please try again later.");

                        log.log(Level.SEVERE, "Register flow failed", ex);
                    });
                    return null;
                });
    }


    @FXML
    void handleBackToLogin(ActionEvent event)
    {
        context.navigation().navigate(LOGIN_FXML);
    }


    private void setLoading(boolean loading)
    {
        if (signUpButton != null)
        {
            signUpButton.setDisable(loading);
        }
        if (usernameField != null)
        {
            usernameField.setDisable(loading);
        }
        if (emailField != null)
        {
            emailField.setDisable(loading);
        }
        if (passwordField != null)
        {
            passwordField.setDisable(loading);
        }
        if (confirmPasswordField != null)
        {
            confirmPasswordField.setDisable(loading);
        }
    }

    private void showError(String message)
    {
        Platform.runLater(() -> {
            if (errorLabel != null) {
                errorLabel.setText(message);
                errorLabel.setVisible(true);
                errorLabel.setManaged(true);
            } else {
                log.warning("UI error (no errorLabel bound in FXML): " + message);
            }
        });
    }

    private void hideError() {
        Platform.runLater(() -> {
            if (errorLabel != null) {
                errorLabel.setText("");
                errorLabel.setVisible(false);
                errorLabel.setManaged(false);
            }
        });
    }

    @FXML
    private void handleOpenGitHub(ActionEvent event) {
        openUrl("https://github.com/mohammadrezaashrafian/X-Clone");
    }

    @FXML
    private void handleOpenDocumentation(ActionEvent event) {
        openUrl("https://github.com/mohammadrezaashrafian/X-Clone/wiki");
    }

    private void openUrl(String url) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(new URI(url));
            }
        } catch (Exception e) {
            e.printStackTrace();
            showError("Could not open link.");
        }
    }
}