package Client.controllers;

import Client.ClientApplicationContext;
import Client.PasswordResetContext;
import Client.PasswordResetContext.VerificationMode;
import Client.navigation.NavigationRoute;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;

import java.awt.*;
import java.net.URI;


public class ForgotPasswordController
{
    private static final NavigationRoute VERIFY_FXML = NavigationRoute.VERIFY_CODE;
    private static final NavigationRoute LOGIN_FXML = NavigationRoute.LOGIN;

    @FXML
    private ImageView logoImageView;

    @FXML
    private TextField emailField;

    @FXML
    private Button resetButton;

    @FXML
    private Hyperlink backToLoginLink;

    @FXML
    private Label errorLabel;

    private final ClientApplicationContext context;

    public ForgotPasswordController(ClientApplicationContext context)
    {
        this.context = context;
    }

    @FXML
    private void initialize()
    {
        // Keep the brand logo in sync with the active theme.
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
    void handleResetPassword(ActionEvent event)
    {
        clearError();

        String email = emailField.getText().trim();
        if (email.isEmpty()) {
            showError("Please enter your email address.");
            return;
        }
        context.getAuthClientService().requestPasswordReset(email)
                .thenAccept(result -> Platform.runLater(() -> {
                    if (result.isSuccess()) {
                        PasswordResetContext.getInstance().setEmail(email);
                        PasswordResetContext.getInstance()
                                .setVerificationMode(PasswordResetContext.VerificationMode.PASSWORD_RESET);
                        context.navigation().navigate(VERIFY_FXML);
                    } else {
                        PasswordResetContext.getInstance().clear();
                        showError(resolveErrorMessage(result.errorCode(), result.errorMessage()));
                    }
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> showError("An unexpected error occurred: " + ex.getMessage()));
                    return null;
                });
    }

    @FXML
    void handleBackToLogin(ActionEvent event)
    {
        PasswordResetContext.getInstance().clear();
        context.navigation().navigate(LOGIN_FXML);
    }

    @FXML
    private String resolveErrorMessage(String errorCode, String errorMessage) {
        if (errorMessage != null && !errorMessage.isBlank()) {
            return errorMessage;
        }
        if (errorCode != null && !errorCode.isBlank()) {
            return "Request failed: " + errorCode;
        }
        return "Failed to request password reset.";
    }

    private void showError(String message)
    {
        Platform.runLater(() -> {
            errorLabel.setText(message);
            errorLabel.setVisible(true);
            errorLabel.setManaged(true);
        });
    }

    private void clearError()
    {
        if (errorLabel != null) {
            errorLabel.setText("");
            errorLabel.setVisible(false);
            errorLabel.setManaged(false);
        }
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