package Client.controllers;

import Client.AvatarLoader;
import Client.ClientApplicationContext;
import Client.navigation.NavigationRoute;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import logic_core.app.dto.response.ProfileInfoResponse;

import java.util.UUID;
import java.util.logging.Logger;

/**
 * Reusable sidebar "Compose New Post" card.
 *
 * The composer itself lives at the top of the timeline, so this card is an
 * entry point: clicking anywhere on it (or on POST) opens Home and focuses
 * the existing composer. No new compose logic is introduced here.
 */
public class ComposeCardController
{
    private static final Logger log = Logger.getLogger(ComposeCardController.class.getName());

    @FXML
    private VBox composeCard;

    @FXML
    private ImageView composeAvatar;

    @FXML
    private Button composePostButton;

    private final ClientApplicationContext context;

    public ComposeCardController(ClientApplicationContext context)
    {
        this.context = context;
    }

    @FXML
    private void initialize()
    {
        composeCard.setOnMouseClicked(event -> openComposer());
        composePostButton.setOnAction(event -> openComposer());

        AvatarLoader.loadDefaultAvatar(composeAvatar);
        loadCurrentUserAvatar();
    }

    /**
     * Reloads the compose card avatar after the current user updates their
     * profile picture.
     */
    public void reloadCurrentUser()
    {
        loadCurrentUserAvatar();
    }

    private void loadCurrentUserAvatar()
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
                    AvatarLoader.loadAvatar(composeAvatar, profile.avatarUrl());
                }))
                .exceptionally(error ->
                {
                    log.warning("Failed to load compose card avatar: " + error.getMessage());
                    return null;
                });
    }

    private void openComposer()
    {
        if (context.navigation() != null)
        {
            context.navigation().navigate(NavigationRoute.HOME);
        }
    }
}
