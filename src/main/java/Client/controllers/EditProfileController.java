package Client.controllers;

import Client.AvatarLoader;
import Client.ClientApplicationContext;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import logic_core.app.dto.media.UploadFile;
import logic_core.app.dto.response.ProfileInfoResponse;
import logic_core.app.dto.response.UpdateCompleteProfileResponse;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class EditProfileController {

    private static final Logger log = Logger.getLogger(EditProfileController.class.getName());

    private static final int MAX_DISPLAY_NAME_LENGTH = 50;
    private static final int MAX_BIO_LENGTH = 160;
    private static final int MAX_AVATAR_SIZE = 5 * 1024 * 1024;
    private static final String DEFAULT_AVATAR_RESOURCE = "/Client/images/user (1).png";

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{3,20}$");

    @FXML
    private Button closeButton;

    @FXML
    private ImageView avatarPreview;

    @FXML
    private Button changeAvatarButton;

    @FXML
    private TextField displayNameField;

    @FXML
    private TextField usernameField;

    @FXML
    private TextArea bioTextArea;

    @FXML
    private Button cancelButton;

    @FXML
    private Button saveButton;

    private final ClientApplicationContext context;
    private File selectedAvatarFile;
    private boolean profileLoaded = false;

    public EditProfileController(ClientApplicationContext context) {
        this.context = context;
    }

    @FXML
    public void initialize() {
        if (!context.session().isLoggedIn()) {
            showError("Session Error", "You are not logged in.");
            disableForm(true);
            return;
        }

        disableForm(true);
        loadCurrentUserData();
    }

    /**
     * Ensures the modal scene uses the same theme system as the rest of the
     * application. Call this once before showing the dialog.
     */
    public void attachToTheme(Scene scene)
    {
        if (scene != null)
        {
            context.getThemeManager().attach(scene);
        }
    }

    @FXML
    private void loadCurrentUserData() {
        UUID userId = context.session().getCurrentUserId();

        if (userId == null) {
            showError("Session Error", "Current user was not found.");
            return;
        }

        context.getUserClientService()
                .getProfile(userId)
                .thenAccept(result -> Platform.runLater(() -> {
                    if (result == null || result.isFailure()) {
                        String error = result == null ? "UNKNOWN_ERROR" : result.getError();
                        log.warning("Failed to load profile: " + error);
                        showError("Load Profile Failed", error != null ? error : "Could not load your profile.");
                        return;
                    }

                    ProfileInfoResponse profile = result.getData();
                    if (profile == null) {
                        showError("Load Profile Failed", "Profile data is empty.");
                        return;
                    }

                    populateFields(profile);
                    profileLoaded = true;
                    disableForm(false);
                }))
                .exceptionally(error -> {
                    log.log(Level.SEVERE, "Critical error while loading profile", error);
                    Platform.runLater(() -> showError("Load Profile Failed", "Connection error."));
                    return null;
                });
    }

    private void populateFields(ProfileInfoResponse profile) {
        displayNameField.setText(valueOrEmpty(profile.displayName()));
        usernameField.setText(valueOrEmpty(profile.username()));
        bioTextArea.setText(valueOrEmpty(profile.bio()));

        loadAvatarPreview(profile.avatarUrl());
    }

    private void loadAvatarPreview(String avatarUrl) {
        AvatarLoader.loadAvatar(avatarPreview, avatarUrl);
    }

    @FXML
    private void handleChangeAvatar(ActionEvent event) {
        if (!profileLoaded) {
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select Profile Image");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Image Files", "*.png", "*.jpg", "*.jpeg")
        );

        Stage stage = (Stage) changeAvatarButton.getScene().getWindow();
        File file = fileChooser.showOpenDialog(stage);

        if (file == null) {
            return;
        }

        try {
            long fileSize = Files.size(file.toPath());
            if (fileSize > MAX_AVATAR_SIZE) {
                showError("Invalid Image", "Avatar size cannot exceed 5 MB.");
                return;
            }

            String contentType = detectContentType(file);
            if (!contentType.startsWith("image/")) {
                showError("Invalid Image", "Please select a valid image file.");
                return;
            }

            selectedAvatarFile = file;
            byte[] fileBytes = Files.readAllBytes(file.toPath());
            Image preview = new Image(new ByteArrayInputStream(fileBytes));
            avatarPreview.setImage(preview);

            log.info("Selected avatar: " + file.getAbsolutePath() + " (" + fileBytes.length + " bytes)");
        } catch (IOException e) {
            log.log(Level.SEVERE, "Failed to read avatar file", e);
            showError("Image Error", "Could not read the selected image.");
        }
    }

    @FXML
    private void handleSave(ActionEvent event) {
        if (!profileLoaded) {
            return;
        }

        String displayName = normalize(displayNameField.getText());
        String username = normalize(usernameField.getText());
        String bio = normalize(bioTextArea.getText());

        String validationError = validateInput(displayName, username, bio);
        if (validationError != null) {
            showError("Invalid Profile", validationError);
            return;
        }

        UploadFile avatar = null;
        if (selectedAvatarFile != null) {
            try {
                avatar = createUploadFile(selectedAvatarFile);
                log.info("Uploading avatar file. Name: " + avatar.fileName() + ", Size: " + avatar.data().length + " bytes");
            } catch (IOException e) {
                log.log(Level.SEVERE, "Failed to prepare avatar upload", e);
                showError("Upload Error", "Could not read the selected avatar.");
                return;
            }
        }

        UUID userId = context.session().getCurrentUserId();
        if (userId == null) {
            showError("Session Error", "Current user was not found.");
            return;
        }

        disableForm(true);
        context.getUserClientService()
                .updateCompleteProfile(
                        userId,
                        displayName,
                        username,
                        bio,
                        avatar,
                        null
                )
                .thenAccept(result -> Platform.runLater(() -> {
                    if (result == null || result.isFailure()) {
                        String error = result == null ? "UNKNOWN_ERROR" : result.getError();
                        log.warning("Profile update failed: " + error);
                        showError("Update Failed", error != null ? error : "Could not update your profile.");
                        disableForm(false);
                        return;
                    }

                    UpdateCompleteProfileResponse response = result.getData();
                    if (response != null && response.profile() != null) {
                        log.info("Profile updated successfully. Returned AvatarUrl: " + response.profile().avatarUrl());

                    // Propagate avatar change to the shell components that own
                    // their own avatar loading so the sidebar and compose card
                    // update immediately instead of waiting for the next mount.
                    if (context.getMainLayoutController() != null)
                    {
                        // Refresh the shell widgets and push the new avatar into
                        // the tweet cards already on screen (no reload needed).
                        context.getMainLayoutController().onCurrentUserProfileUpdated(
                                response.profile().avatarUrl()
                        );
                    }
                    } else {
                        log.info("Profile updated successfully.");
                    }

                    closeModal();
                }))
                .exceptionally(error -> {
                    log.log(Level.SEVERE, "Critical error while updating profile", error);
                    Platform.runLater(() -> {
                        showError("Update Failed", "Connection error.");
                        disableForm(false);
                    });
                    return null;
                });
    }

    @FXML
    private void handleCancel(ActionEvent event) {
        closeModal();
    }

    private void closeModal() {
        if (saveButton == null || saveButton.getScene() == null) {
            return;
        }

        Stage stage = (Stage) saveButton.getScene().getWindow();
        if (stage != null) {
            stage.close();
        }
    }

    private String validateInput(String displayName, String username, String bio) {
        if (displayName.isBlank()) {
            return "Display name cannot be empty.";
        }

        if (displayName.length() > MAX_DISPLAY_NAME_LENGTH) {
            return "Display name must be at most " + MAX_DISPLAY_NAME_LENGTH + " characters.";
        }

        if (username.isBlank()) {
            return "Username cannot be empty.";
        }

        if (!USERNAME_PATTERN.matcher(username).matches()) {
            return "Username must be 3-20 characters and contain only letters, numbers, underscore, or hyphen.";
        }

        if (bio.length() > MAX_BIO_LENGTH) {
            return "Bio must be at most " + MAX_BIO_LENGTH + " characters.";
        }

        return null;
    }

    private UploadFile createUploadFile(File file) throws IOException {
        byte[] data = Files.readAllBytes(file.toPath());
        String contentType = detectContentType(file);

        return new UploadFile(
                file.getName(),
                contentType,
                data
        );
    }

    private String detectContentType(File file) {
        try {
            String contentType = Files.probeContentType(file.toPath());
            if (contentType != null && !contentType.isBlank()) {
                return contentType;
            }
        } catch (IOException ignored) {
        }

        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }

        return "application/octet-stream";
    }

    private void disableForm(boolean disabled) {
        displayNameField.setDisable(disabled);
        usernameField.setDisable(disabled);
        bioTextArea.setDisable(disabled);
        changeAvatarButton.setDisable(disabled);
        saveButton.setDisable(disabled);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message == null ? "Unknown error." : message);
        alert.showAndWait();
    }
}