package Client.controllers;

import Client.AvatarLoader;
import Client.ClientApplicationContext;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import logic_core.app.dto.request.GetTimelineResponse;
import logic_core.app.dto.timeline.TimelineTweet;
import logic_core.domain.repository.TimelineType;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;


public class TimelineController implements MainLayoutController.LiveAvatarAware {

    private static final Logger log = Logger.getLogger(TimelineController.class.getName());

    @FXML
    private ImageView currentUserAvatar;

    @FXML
    private TextField newTweetField;

    @FXML
    private Button submitTweetButton;

    @FXML
    private VBox tweetsContainer;

    @FXML
    private ImageView mediaPreview;

    @FXML
    private Button addMediaButton;

    @FXML
    private Button addPollButton;

    private final ClientApplicationContext context;

    /** Cached current-user avatar URL so tweet cards stay in sync. */
    private String currentUserAvatarUrl;


    public TimelineController(ClientApplicationContext context) {
        this.context = context;
    }

    @FXML
    public void initialize() {
        // Disable media and poll buttons since features are not yet implemented
        if (addMediaButton != null) {
            addMediaButton.setDisable(true);
        }
        if (addPollButton != null) {
            addPollButton.setDisable(true);
        }

        loadCurrentUserProfile();
        loadTimelineTweets();
    }

    @FXML
    void handleSubmitTweet(ActionEvent event) {
        String tweetContent = newTweetField.getText();

        if (tweetContent == null || tweetContent.trim().isEmpty()) {
            return;
        }

        submitTweetButton.setDisable(true);

        context.getTweetService().createTweet(
                tweetContent.trim(),
                null,
                null,
                List.of(),
                null
        ).thenAccept(result ->
                Platform.runLater(() ->
                {
                    submitTweetButton.setDisable(false);

                    if (result == null || result.isFailure())
                    {
                        log.warning("Create Tweet failed : " + (result == null ? "" : result.getError()));
                        return;
                    }
                    newTweetField.clear();

                    loadTimelineTweets();
                })
        ).exceptionally(ex ->
        {
            Platform.runLater(() ->
                    submitTweetButton.setDisable(false));

            log.severe(ex.getMessage());

            return null;
        });
    }

    private void loadCurrentUserProfile() {
        if (!context.session().isLoggedIn()) {
            return;
        }

        UUID currentUserId = context.getSnapshot().userId();
        if (currentUserId == null) {
            return;
        }

        context.getUserClientService()
                .getProfile(currentUserId)
                .thenAccept(result -> Platform.runLater(() -> {
                    if (result == null || result.isFailure() || result.getData() == null) {
                        return;
                    }

                    logic_core.app.dto.response.ProfileInfoResponse profile = result.getData();
                    currentUserAvatarUrl = profile.avatarUrl();
                    AvatarLoader.loadAvatar(currentUserAvatar, currentUserAvatarUrl);
                }))
                .exceptionally(error -> {
                    log.warning("Failed to load current user profile: " + error.getMessage());
                    return null;
                });
    }

    /**
     * Pushes a changed current-user avatar into the cards already on screen, so
     * the user's own tweets update without the timeline being reloaded.
     */
    @Override
    public void applyCurrentUserAvatar(String avatarUrl)
    {
        if (avatarUrl == null || avatarUrl.isBlank() || tweetsContainer == null)
        {
            return;
        }

        currentUserAvatarUrl = avatarUrl;

        for (Node node : tweetsContainer.getChildren())
        {
            Object card = node.getProperties().get(TweetItemController.NODE_KEY);

            if (card instanceof TweetItemController tweetItem)
            {
                tweetItem.applyCurrentUserAvatar(avatarUrl);
            }
        }
    }


    private void loadTimelineTweets() {
        if (!context.session().isLoggedIn()) {
            return;
        }

        UUID currentUser = context.getSnapshot().userId();

        context.getTimelineService().getTimeline(
                TimelineType.HOME,
                currentUser,
                null,
                0,
                20
        ).thenAccept(result ->
                Platform.runLater(() ->
                {
                    tweetsContainer.getChildren().clear();

                    if (result == null || result.isFailure())
                    {
                        log.warning("Timeline Error : "
                                + (result == null ? "" : result.getError()));

                        showEmptyState("Unable to load timeline.");
                        return;
                    }

                    GetTimelineResponse response =
                            result.getData();

                    if (response == null
                            || response.tweets() == null
                            || response.tweets().isEmpty())
                    {
                        showEmptyState(
                                "No posts yet! Your timeline is empty."
                        );
                        return;
                    }

                    for (TimelineTweet tweet : response.tweets())
                    {
                        addTweetCard(tweet);
                    }

                })
        ).exceptionally(ex ->
        {
            Platform.runLater(() ->
                    showEmptyState(
                            "Something went wrong while loading timeline."
                    ));

            log.severe(ex.getMessage());

            return null;
        });
    }

    private void addTweetCard(TimelineTweet tweet) {

        try {

            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/Client/fxml/TweetItem.fxml")
            );

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

            Node node = loader.load();

            TweetItemController controller = loader.getController();

            // Let the shell reach this card later, for in-place avatar updates.
            node.getProperties().put(TweetItemController.NODE_KEY, controller);

            // For the current user's own tweets, use the live profile avatar
            // so avatar changes are reflected without a full page reload.
            String avatarUrl = tweet.avatarUrl();
            UUID currentUserId = context.session().getCurrentUserId();
            if (currentUserId != null && currentUserId.equals(tweet.authorId())
                    && currentUserAvatarUrl != null) {
                avatarUrl = currentUserAvatarUrl;
            }

            controller.preloadAvatar(avatarUrl);

            TimelineTweet effectiveTweet = tweet;

            if (currentUserAvatarUrl != null && currentUserId != null
                    && currentUserId.equals(tweet.authorId())) {
                effectiveTweet = TimelineTweet.builder()
                        .tweetId(tweet.tweetId())
                        .authorId(tweet.authorId())
                        .username(tweet.username())
                        .displayName(tweet.displayName())
                        .avatarUrl(currentUserAvatarUrl)
                        .content(tweet.content())
                        .likeCount(tweet.likeCount())
                        .replyCount(tweet.replyCount())
                        .retweetCount(tweet.retweetCount())
                        .isLiked(tweet.isLiked())
                        .publishedAt(tweet.publishedAt())
                        .media(tweet.media())
                        .poll(tweet.poll())
                        .build();
            }

            controller.setTweet(effectiveTweet);

            controller.setOnDeleteSuccess(() -> {
                tweetsContainer.getChildren().remove(node);
                if (tweetsContainer.getChildren().isEmpty()) {
                    showEmptyState("No posts yet! Your timeline is empty.");
                }
            });

            tweetsContainer.getChildren().add(node);

        } catch (IOException e) {
            log.severe(e.getMessage());
        }
    }

    private void showEmptyState(String message)
    {
        tweetsContainer.getChildren().clear();
        Label label = new Label(message);
        label.getStyleClass().add("empty-state");
        tweetsContainer.getChildren().add(label);
    }

    @FXML
    void handleSelectMedia()
    {
        // Media upload not yet implemented - button is disabled in FXML
    }

    @FXML
    void handleCreatePoll()
    {
        // Poll creation not yet implemented - button is disabled in FXML
    }
}