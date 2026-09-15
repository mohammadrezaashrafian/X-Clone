package Client.controllers;

import Client.AvatarLoader;
import Client.ClientApplicationContext;
import Client.navigation.NavigationRoute;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import logic_core.app.dto.request.GetTimelineResponse;
import logic_core.app.dto.response.ProfileInfoResponse;
import logic_core.app.dto.timeline.TimelineTweet;
import logic_core.domain.repository.TimelineType;

import java.io.IOException;
import java.util.UUID;
import java.util.logging.Logger;

public class ProfileController implements MainLayoutController.LiveAvatarAware {

    private static final Logger log = Logger.getLogger(ProfileController.class.getName());

    @FXML
    private Label followersCountLabel;

    @FXML
    private Label followingCountLabel;

    @FXML
    private Label headerNameLabel;

    @FXML
    private ImageView profileAvatar;

    @FXML
    private Button editProfileButton;

    @FXML
    private Label displayNameLabel;

    @FXML
    private Label usernameLabel;

    @FXML
    private Label bioLabel;

    @FXML
    private VBox userTweetsContainer;

    private final ClientApplicationContext context;
    private UUID profileUserId;

    public ProfileController(ClientApplicationContext context) {
        this.context = context;
    }

    @FXML
    public void initialize() {
        if (!context.session().isLoggedIn()) {
            return;
        }

        this.profileUserId = context.session().getCurrentUserId();

        loadUserProfileData();
        loadUserTweets();
    }

    private void loadUserProfileData() {
        context.getUserClientService()
                .getProfile(profileUserId)
                .thenAccept(result -> {
                    Platform.runLater(() -> {
                        if (result == null || result.isFailure()) {
                            log.warning("Profile loading failed : " + (result == null ? "null" : result.getError()));
                            return;
                        }

                        ProfileInfoResponse profile = result.getData();
                        if (profile == null) return;

                        headerNameLabel.setText(safe(profile.displayName()));
                        displayNameLabel.setText(safe(profile.displayName()));
                        usernameLabel.setText(profile.username() == null ? "" : "@" + profile.username());
                        bioLabel.setText(safe(profile.bio()));
                        followersCountLabel.setText(String.valueOf(profile.followers()));
                        followingCountLabel.setText(String.valueOf(profile.following()));

                        loadAvatar(profile);
                    });
                })
                .exceptionally(error -> {
                    log.severe("Profile exception : " + error.getMessage());
                    return null;
                });
    }

    private void loadAvatar(ProfileInfoResponse profile) {
        if (profileAvatar == null) return;

        String avatarUrl = profile.avatarUrl();
        log.info("Loading Avatar for profile. avatarUrl = " + avatarUrl);

        AvatarLoader.loadAvatar(profileAvatar, avatarUrl);
    }

    private void loadUserTweets() {
        context.getTimelineService()
                .getTimeline(
                        TimelineType.USER,
                        profileUserId,
                        profileUserId,
                        0,
                        20
                )
                .thenAccept(result -> {
                    Platform.runLater(() -> {
                        userTweetsContainer.getChildren().clear();

                        if (result == null || result.isFailure()) {
                            showEmptyState("Unable to load tweets");
                            return;
                        }

                        GetTimelineResponse response = result.getData();

                        if (response == null || response.tweets() == null || response.tweets().isEmpty()) {
                            showEmptyState("No tweets yet");
                            return;
                        }

                        for (TimelineTweet tweet : response.tweets()) {
                            addTweetCard(tweet);
                        }
                    });
                })
                .exceptionally(error -> {
                    Platform.runLater(() -> showEmptyState("Something went wrong"));
                    return null;
                });
    }

    private void addTweetCard(TimelineTweet tweet) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/Client/fxml/TweetItem.fxml"));

            loader.setControllerFactory(type -> {
                if (type == TweetItemController.class) {
                    return new TweetItemController(context);
                }
                try {
                    return type.getDeclaredConstructor().newInstance();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            Node card = loader.load();
            TweetItemController controller = loader.getController();

            // Let the shell reach this card later, for in-place avatar updates.
            card.getProperties().put(TweetItemController.NODE_KEY, controller);

            controller.preloadAvatar(tweet.avatarUrl());

            controller.setTweet(tweet);

            controller.setOnDeleteSuccess(() -> {
                userTweetsContainer.getChildren().remove(card);
                if (userTweetsContainer.getChildren().isEmpty()) {
                    showEmptyState("No tweets yet");
                }
            });

            userTweetsContainer.getChildren().add(card);
        } catch (IOException e) {
            log.severe("Tweet card error : " + e.getMessage());
        }
    }

    /**
     * Pushes a changed current-user avatar into the tweet cards already on this
     * profile, so they update without reloading the page.
     */
    @Override
    public void applyCurrentUserAvatar(String avatarUrl)
    {
        if (avatarUrl == null || avatarUrl.isBlank() || userTweetsContainer == null)
        {
            return;
        }

        for (Node node : userTweetsContainer.getChildren())
        {
            Object card = node.getProperties().get(TweetItemController.NODE_KEY);

            if (card instanceof TweetItemController tweetItem)
            {
                tweetItem.applyCurrentUserAvatar(avatarUrl);
            }
        }
    }

    @FXML
    private void handleEditProfile(ActionEvent event) {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/Client/fxml/EditProfile.fxml"));

            loader.setControllerFactory(type -> {
                if (type == EditProfileController.class) {
                    return new EditProfileController(context);
                }
                try {
                    return type.getDeclaredConstructor().newInstance();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            Parent root = loader.load();
            Stage dialog = new Stage();
            dialog.setTitle("Edit Profile");
            dialog.initOwner((Stage) editProfileButton.getScene().getWindow());
            dialog.initModality(Modality.APPLICATION_MODAL);
            Scene dialogScene = new Scene(root);
            context.getThemeManager().attach(dialogScene);
            dialog.setScene(dialogScene);
            dialog.setResizable(false);
            dialog.showAndWait();

            loadUserProfileData();
        } catch (IOException e) {
            log.severe(e.getMessage());
        }
    }

    @FXML
    private void handleShowFollowers()
    {
        navigateTo(NavigationRoute.FOLLOWERS);
    }

    @FXML
    private void handleShowFollowing()
    {
        navigateTo(NavigationRoute.FOLLOWING);
    }

    private void navigateTo(NavigationRoute route)
    {
        if (context.navigation() != null)
        {
            context.navigation().navigate(route);
        }
    }

    private void showEmptyState(String text) {
        userTweetsContainer.getChildren().clear();
        Label label = new Label(text);
        label.getStyleClass().add("empty-state");
        userTweetsContainer.getChildren().add(label);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}