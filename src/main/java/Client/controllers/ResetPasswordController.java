package Client.controllers;

import Client.ClientApplicationContext;
import Client.PasswordResetContext;
import Client.theme.Theme;
import Client.theme.ThemeManager;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.image.ImageView;

import java.awt.*;
import java.net.URI;

public class ResetPasswordController
{
    private static final Client.navigation.NavigationRoute LOGIN_FXML = Client.navigation.NavigationRoute.LOGIN;

    @FXML
    private PasswordField newPasswordField;

    @FXML
    private PasswordField confirmPasswordField;

    @FXML
    private Button resetPasswordButton;

    @FXML
    private Hyperlink backToLoginLink;

    @FXML
    private Label errorLabel;

    @FXML
    private ImageView logoImageView;

    private final ClientApplicationContext context;

    public ResetPasswordController(ClientApplicationContext context)
    {
        this.context = context;
    }

    @FXML
    void handleResetPassword(ActionEvent event)
    {
        clearError();

        String newPassword = newPasswordField.getText() != null ? newPasswordField.getText().trim() : "";
        String confirmPassword = confirmPasswordField.getText() != null ? confirmPasswordField.getText().trim() : "";

        if (newPassword.isEmpty())
        {
            showError("Please enter a new password.");
            return;
        }

        if (newPassword.length() < 6)
        {
            showError("Password must be at least 6 characters.");
            return;
        }

        if (!newPassword.equals(confirmPassword))
        {
            showError("Passwords do not match.");
            return;
        }

        String email = PasswordResetContext.getInstance().getEmail();
        String code = PasswordResetContext.getInstance().getCode();

        if (email == null || email.isBlank() || code == null || code.isBlank())
        {
            context.navigation().navigate(LOGIN_FXML);
            return;
        }

        setLoading(true);

        context.getAuthClientService().resetPassword(email, code, newPassword)
                .thenAccept(result -> Platform.runLater(() -> {
                    setLoading(false);

                    if (result.isSuccess())
                    {
                        PasswordResetContext.getInstance().clear();
                        context.navigation().navigate(LOGIN_FXML);
                    }
                    else
                    {
                        showError(resolveErrorMessage(result.errorCode(), result.errorMessage()));
                    }
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        setLoading(false);
                        showError("Connection error. Please try again later.");
                    });
                    return null;
                });
    }

    @FXML
    void handleBackToLogin(ActionEvent event)
    {
        PasswordResetContext.getInstance().clear();
        context.navigation().navigate(LOGIN_FXML);
    }

    private String resolveErrorMessage(String errorCode, String errorMessage)
    {
        if (errorMessage != null && !errorMessage.isBlank())
        {
            return errorMessage;
        }
        if (errorCode != null && !errorCode.isBlank())
        {
            return "Reset failed: " + errorCode;
        }
        return "Failed to reset password.";
    }

    private void setLoading(boolean loading)
    {
        if (resetPasswordButton != null)
        {
            resetPasswordButton.setDisable(loading);
        }
        if (newPasswordField != null)
        {
            newPasswordField.setDisable(loading);
        }
        if (confirmPasswordField != null)
        {
            confirmPasswordField.setDisable(loading);
        }
    }

    private void showError(String message)
    {
        if (errorLabel != null)
        {
            errorLabel.setText(message);
            errorLabel.setVisible(true);
            errorLabel.setManaged(true);
        }
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

    private void clearError()
    {
        if (errorLabel != null)
        {
            errorLabel.setText("");
            errorLabel.setVisible(false);
            errorLabel.setManaged(false);
        }
    }

    @FXML
    private void handleOpenGitHub() {
        openUrl("https://github.com/mohammadrezaashrafian/X-Clone");
    }

    @FXML
    private void handleOpenDocumentation() {
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