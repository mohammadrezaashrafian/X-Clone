package Client.controllers;

import Client.AvatarLoader;
import Client.ClientApplicationContext;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import logic_core.app.dto.response.UserSummaryResponse;

import java.util.UUID;

public class UserItemController
{

    @FXML
    private HBox rootContainer;

    @FXML
    private ImageView avatarImageView;

    @FXML
    private Label displayNameLabel;

    @FXML
    private Label usernameLabel;

    @FXML
    private Button followButton;

    @FXML
    private Button messageButton;

    private final ClientApplicationContext context;

    private UserSummaryResponse user;


    private boolean isFollowing = false;

    public UserItemController(ClientApplicationContext context)
    {
        this.context = context;
    }

    @FXML
    private void initialize()
    {
        rootContainer.setOnMouseClicked(e -> openProfile());

        followButton.setOnAction(e -> toggleFollow());

        messageButton.setOnAction(e -> startConversation());
    }

    public void setUser(UserSummaryResponse user) {
        this.user = user;

        if (user == null)
            return;

        displayNameLabel.setText(
                user.displayName() == null
                        ? ""
                        : user.displayName()
        );

        usernameLabel.setText(
                user.username() == null
                        ? ""
                        : "@" + user.username()
        );

        loadAvatar(user.avatarUrl());

        hideButtonIfCurrentUser();


        updateFollowButton();
    }


    private void loadFollowState() {

        if (user == null)
            return;

        followButton.setDisable(true);

        context.getUserClientService()
                .isFollow(user.userId())
                .thenAccept(result -> Platform.runLater(() -> {

                    followButton.setDisable(false);

                    if (result.isFailure())
                        return;

                    isFollowing = result.getData().following();
                    updateFollowButton();

                }))
                .exceptionally(error -> {

                    Platform.runLater(() ->
                            followButton.setDisable(false));

                    return null;
                });
    }

    private void toggleFollow() {

        if (user == null)
            return;

        followButton.setDisable(true);

        boolean oldState = isFollowing;

        isFollowing = !isFollowing;
        updateFollowButton();

        var future = isFollowing
                ? context.getRelationClientService().follow(user.userId())
                : context.getRelationClientService().unfollow(user.userId());

        future.thenAccept(result ->
                Platform.runLater(() -> {

                    followButton.setDisable(false);

                    if (result.isFailure()) {

                        isFollowing = oldState;
                        updateFollowButton();

                        return;
                    }

                    reloadFollowWidgets();

                })
        ).exceptionally(ex -> {

            Platform.runLater(() -> {

                followButton.setDisable(false);

                isFollowing = oldState;
                updateFollowButton();
            });

            return null;
        });
    }

    private void updateFollowButton()
    {
        followButton.getStyleClass().removeAll("btn-dark", "btn-outline");
        if (isFollowing)
        {
            followButton.setText("Following");
            followButton.getStyleClass().add("btn-outline");
        }
        else
        {
            followButton.setText("Follow");
            followButton.getStyleClass().add("btn-dark");
        }
    }

    private void openProfile()
    {
        if (user == null)
            return;

        if (context.navigation() != null)
        {
            context.navigation().navigate(Client.navigation.NavigationRoute.PROFILE);
        }
    }

    private void hideButtonIfCurrentUser()
    {
        UUID currentUser =
                context.getSnapshot().userId();

        if (currentUser != null &&
                currentUser.equals(user.userId()))
        {
            followButton.setVisible(false);
            followButton.setManaged(false);

            messageButton.setVisible(false);
            messageButton.setManaged(false);
        }
    }

    /**
     * The follow graph changed, so the shell widgets that read it must refetch.
     */
    private void reloadFollowWidgets()
    {
        MainLayoutController shell = context.getMainLayoutController();

        if (shell != null)
        {
            shell.reloadFollowWidgets();
        }
    }

    private void loadAvatar(String avatarUrl)
    {
        AvatarLoader.loadAvatar(avatarImageView, avatarUrl);
    }

    private void startConversation() {

        if (user == null || user.userId() == null)
            return;

        MainLayoutController shell = context.getMainLayoutController();

        if (shell != null)
        {
            // Ask the shell to open Messages with this user's conversation.
            shell.requestConversationWith(user.userId());
            return;
        }

        if (context.navigation() != null)
        {
            context.navigation().navigate(Client.navigation.NavigationRoute.MESSAGES);
        }
    }
}