package Client.controllers;

import Client.ClientApplicationContext;
import Client.PasswordResetContext;
import Client.PasswordResetContext.VerificationMode;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;

import java.awt.*;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.logging.Logger;

public class VerifyCodeController
{    private static final Client.navigation.NavigationRoute RESET_PASSWORD_FXML = Client.navigation.NavigationRoute.RESET_PASSWORD;
    private static final Client.navigation.NavigationRoute LOGIN_FXML = Client.navigation.NavigationRoute.LOGIN;
    private static final Client.navigation.NavigationRoute FORGOT_PASSWORD_FXML = Client.navigation.NavigationRoute.FORGOT_PASSWORD;

    private static final String GITHUB_URL = "https://github.com/mohammadrezaashrafian";
    private static final String DOCUMENTATION_URL = "https://github.com/mohammadrezaashrafian/x-clone";

    private static final Logger log = Logger.getLogger(LoginController.class.getName());


    @FXML
    private TextField verificationCodeField;

    @FXML
    private Button verifyButton;

    @FXML
    private Hyperlink resendCodeLink;

    @FXML
    private Hyperlink backToLoginLink;

    @FXML
    private Label infoLabel;

    @FXML
    private VBox infoContainer;

    @FXML
    private ImageView logoImageView;

    private final ClientApplicationContext context;

    public VerifyCodeController(ClientApplicationContext context)
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
    void handleVerifyCode(ActionEvent event)
    {
        clearMessages();

        String code = verificationCodeField.getText() != null ? verificationCodeField.getText().trim() : "";

        if (code.isEmpty())
        {
            showError("Please enter the verification code.");
            return;
        }
        else if (code.length() != 6)
        {
            showError("Verification code must be 6 digits.");
            return;
        }

        VerificationMode mode = PasswordResetContext.getInstance().getVerificationMode();

        if (mode == VerificationMode.REGISTRATION)
        {
            handleRegistrationVerification(code);
        }
        else
        {
            handlePasswordResetVerification(code);
        }
    }

    private void handlePasswordResetVerification(String code)
    {
        String email = PasswordResetContext.getInstance().getEmail();

        if (email == null || email.isBlank())
        {
            context.navigation().navigate(FORGOT_PASSWORD_FXML);
            return;
        }

        setLoading(true);

        context.getAuthClientService().verifyPasswordResetCode(email, code)
                .thenAccept(result -> Platform.runLater(() -> {
                    setLoading(false);

                    if (result.isSuccess())
                    {
                        PasswordResetContext.getInstance().setCode(code);
                        context.navigation().navigate(RESET_PASSWORD_FXML);
                    }
                    else
                    {
                        showError(resolveErrorMessage(result.errorCode(), result.errorMessage()));
                    }
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        setLoading(false);
                        showError("Connection error: " + ex.getMessage());
                    });
                    return null;
                });
    }

    private void handleRegistrationVerification(String code)
    {
        setLoading(true);

        context.getAuthClientService().confirmEmailVerification(code)
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
                        showError("Connection error: " + ex.getMessage());
                    });
                    return null;
                });
    }

    @FXML
    void handleResendCode(ActionEvent event)
    {
        clearMessages();

        VerificationMode mode = PasswordResetContext.getInstance().getVerificationMode();

        if (mode == VerificationMode.REGISTRATION)
        {
            handleResendRegistrationVerification();
        }
        else
        {
            handleResendPasswordReset();
        }
    }

    private void handleResendPasswordReset()
    {
        String email = PasswordResetContext.getInstance().getEmail();
        if (email == null || email.isBlank())
        {
            PasswordResetContext.getInstance().clear();
            context.navigation().navigate(FORGOT_PASSWORD_FXML);
            return;
        }

        setLoading(true);

        context.getAuthClientService().requestPasswordReset(email)
                .thenAccept(result -> Platform.runLater(() -> {
                    setLoading(false);

                    if (result.isSuccess())
                    {
                        showInfo("A new verification code has been sent.");
                        verificationCodeField.clear();
                        PasswordResetContext.getInstance().setCode(null);
                    }
                    else
                    {
                        showError(resolveErrorMessage(result.errorCode(), result.errorMessage()));
                    }
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        setLoading(false);
                        showError("Connection error: " + ex.getMessage());
                    });
                    return null;
                });
    }

    private void handleResendRegistrationVerification()
    {
        setLoading(true);

        context.getAuthClientService().requestEmailVerification()
                .thenAccept(result -> Platform.runLater(() -> {
                    setLoading(false);

                    if (result.isSuccess())
                    {
                        showInfo("A new verification code has been sent.");
                        verificationCodeField.clear();
                    }
                    else
                    {
                        showError(resolveErrorMessage(result.errorCode(), result.errorMessage()));
                    }
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        setLoading(false);
                        showError("Connection error: " + ex.getMessage());
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

    private String resolveErrorMessage(String errorCode, String errorMessage) {
        if (errorMessage != null && !errorMessage.isBlank()) {
            return errorMessage;
        }
        if (errorCode != null && !errorCode.isBlank()) {
            return "Verification failed: " + errorCode;
        }
        return "Invalid verification code.";
    }

    private void setLoading(boolean loading)
    {
        if (verifyButton != null) {
            verifyButton.setDisable(loading);
        }
        if (verificationCodeField != null) {
            verificationCodeField.setDisable(loading);
        }
        if (resendCodeLink != null) {
            resendCodeLink.setDisable(loading);
        }
    }

    private void showError(String message)
    {
        if (infoContainer != null) {
            infoContainer.setVisible(true);
            infoContainer.setManaged(true);
        }
        if (infoLabel != null) {
            infoLabel.setText(message);
            infoLabel.setVisible(true);
        }
    }

    private void showInfo(String message)
    {
        if (infoContainer != null) {
            infoContainer.setVisible(true);
            infoContainer.setManaged(true);
        }
        if (infoLabel != null) {
            infoLabel.setText(message);
            infoLabel.setVisible(true);
        }
    }

    private void clearMessages()
    {
        if (infoLabel != null) {
            infoLabel.setText("");
            infoLabel.setVisible(false);
        }
        if (infoContainer != null) {
            infoContainer.setVisible(false);
            infoContainer.setManaged(false);
        }
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

}