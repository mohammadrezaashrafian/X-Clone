package Client.controllers;

import Client.AvatarLoader;
import Client.ClientApplicationContext;
import Client.navigation.NavigationRoute;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import logic_core.app.dto.response.ProfileInfoResponse;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * Reusable current-user card shown at the bottom of the navigation sidebar.
 *
 * The component loads its own data (avatar, display name, handle) so hosts
 * only need to include the FXML, and it navigates through the existing
 * {@code NavigationManager} exactly like every other view.
 */
public class ProfileCardController
{
    private static final Logger log = Logger.getLogger(ProfileCardController.class.getName());

    @FXML
    private HBox profileCard;

    @FXML
    private ImageView profileAvatar;

    @FXML
    private Label profileName;

    @FXML
    private Label profileHandle;

    @FXML
    private Button profileActionButton;

    private final ClientApplicationContext context;

    public ProfileCardController(ClientApplicationContext context)
    {
        this.context = context;
    }

    @FXML
    private void initialize()
    {
        if (profileCard != null)
        {
            profileCard.setOnMouseClicked(event -> openProfile());
        }

        if (profileActionButton != null)
        {
            profileActionButton.setOnAction(event -> openProfile());
        }

        AvatarLoader.loadDefaultAvatar(profileAvatar);
        loadCurrentUser();
    }

    /**
     * Reloads the sidebar profile card when the session/user identity changes.
     * This keeps the card consistent with the rest of the application after
     * login, logout, or session refresh.
     */
    public void reloadCurrentUser()
    {
        AvatarLoader.loadDefaultAvatar(profileAvatar);
        loadCurrentUser();
    }

    private void loadCurrentUser()
    {
        if (!context.session().isLoggedIn())
        {
            return;
        }

        UUID currentUserId = context.getSnapshot().userId();

        if (currentUserId == null)
        {
            return;
        }

        context.getUserClientService()
                .getProfile(currentUserId)
                .thenAccept(result -> Platform.runLater(() ->
                {
                    if (result == null || result.isFailure() || result.getData() == null)
                    {
                        return;
                    }

                    ProfileInfoResponse profile = result.getData();

                    if (profileName != null)
                    {
                        profileName.setText(safe(profile.displayName()));
                    }

                    if (profileHandle != null)
                    {
                        profileHandle.setText("@" + safe(profile.username()));
                    }

                    AvatarLoader.loadAvatar(profileAvatar, profile.avatarUrl());
                }))
                .exceptionally(error ->
                {
                    log.warning("Failed to load sidebar profile card: " + error.getMessage());
                    return null;
                });
    }

    private void openProfile()
    {
        if (context.navigation() != null)
        {
            context.navigation().navigate(NavigationRoute.PROFILE);
        }
    }

    private String safe(String value)
    {
        return value == null ? "" : value;
    }
}
