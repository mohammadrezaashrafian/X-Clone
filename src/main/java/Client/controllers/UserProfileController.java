package Client.controllers;

import Client.ClientApplicationContext;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import logic_core.app.dto.response.ProfileInfoResponse;

import java.util.UUID;

public class UserProfileController {

    @FXML private Label headerNameLabel;
    @FXML private Label tweetCountLabel;
    @FXML private ImageView userAvatarImageView;
    @FXML private Button followButton;
    @FXML private Label displayNameLabel;
    @FXML private Label usernameLabel;
    @FXML private Label bioLabel;
    @FXML private Label followingCountLabel;
    @FXML private Label followersCountLabel;
    @FXML private VBox userTweetsContainer;

    private final ClientApplicationContext context;

    private String targetUsername;
    private UUID userId;

    private boolean isFollowing;
    private long followersCount;

    public UserProfileController(ClientApplicationContext context) {
        this.context = context;
    }

    @FXML
    public void initialize() {

        followingCountLabel.setOnMouseClicked(e -> openUserList("Following", false));

        followersCountLabel.setOnMouseClicked(e -> openUserList("Followers", true));
    }

    public void setProfileData(ProfileInfoResponse profile) {

        this.userId = profile.userId();
        this.targetUsername = profile.username();
        this.followersCount = profile.followers();

        headerNameLabel.setText(nullSafe(profile.displayName()));
        displayNameLabel.setText(nullSafe(profile.displayName()));
        usernameLabel.setText("@" + nullSafe(profile.username()));
        bioLabel.setText(nullSafe(profile.bio()));

        tweetCountLabel.setText(profile.tweets() + " Tweets");
        followingCountLabel.setText(profile.following() + " Following");

        updateFollowersLabel();
        checkIfSelfProfile();
        loadFollowState();
    }

    private void loadFollowState() {

        if (userId == null)
            return;

        followButton.setDisable(true);

        context.getUserClientService()
                .isFollow(userId)
                .thenAccept(result -> Platform.runLater(() -> {

                    followButton.setDisable(false);

                    if (result.isFailure())
                        return;

                    isFollowing = result.getData().following();
                    updateFollowButtonState();

                }))
                .exceptionally(error -> {

                    Platform.runLater(() ->
                            followButton.setDisable(false));

                    return null;
                });
    }

    @FXML
    public void handleFollowToggle(ActionEvent event) {

        if (userId == null)
            return;

        followButton.setDisable(true);

        boolean oldState = isFollowing;
        long oldFollowersCount = followersCount;

        isFollowing = !isFollowing;
        followersCount += isFollowing ? 1 : -1;

        if (followersCount < 0)
            followersCount = 0;

        updateFollowersLabel();
        updateFollowButtonState();

        var future = isFollowing
                ? context.getRelationClientService().follow(userId)
                : context.getRelationClientService().unfollow(userId);

        future.thenAccept(result -> Platform.runLater(() -> {

            followButton.setDisable(false);

            if (result.isFailure()) {

                isFollowing = oldState;
                followersCount = oldFollowersCount;

                updateFollowersLabel();
                updateFollowButtonState();

                return;
            }

            reloadFollowWidgets();

        })).exceptionally(error -> {

            Platform.runLater(() -> {

                followButton.setDisable(false);

                isFollowing = oldState;
                followersCount = oldFollowersCount;

                updateFollowersLabel();
                updateFollowButtonState();
            });

            return null;
        });
    }

    /**
     * The follow graph changed, so the shell widgets that read it must refetch.
     */
    private void reloadFollowWidgets() {

        MainLayoutController shell = context.getMainLayoutController();

        if (shell != null) {
            shell.reloadFollowWidgets();
        }
    }

    private void updateFollowersLabel() {
        followersCountLabel.setText(followersCount + " Followers");
    }

    private void updateFollowButtonState() {
        followButton.getStyleClass().removeAll("user-profile-follow-btn", "user-profile-following-btn");
        if (isFollowing) {
            followButton.setText("Following");
            followButton.getStyleClass().add("user-profile-following-btn");
        } else {
            followButton.setText("Follow");
            followButton.getStyleClass().add("user-profile-follow-btn");
        }
    }

    private void checkIfSelfProfile() {

        try {

            String currentUsername = context.getSnapshot().username();

            boolean self =
                    currentUsername != null &&
                            currentUsername.equals(targetUsername);

            followButton.setVisible(!self);
            followButton.setManaged(!self);

        } catch (Exception ignored) {
        }
    }

    private void openUserList(String title, boolean isFollowers) {

        if (context == null || context.navigation() == null)
            return;

        if (isFollowers)
        {
            context.navigation().navigate(Client.navigation.NavigationRoute.FOLLOWERS);
        }
        else
        {
            context.navigation().navigate(Client.navigation.NavigationRoute.FOLLOWING);
        }
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}