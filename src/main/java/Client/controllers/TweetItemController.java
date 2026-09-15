package Client.controllers;

import Client.AvatarLoader;
import Client.ClientApplicationContext;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import logic_core.app.dto.response.LikeResponse;
import logic_core.app.dto.timeline.TimelineTweet;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Logger;

public class TweetItemController {

    private static final Logger log = Logger.getLogger(TweetItemController.class.getName());

    /** Entry-state style class, see components/animations.css. */
    private static final String ANIM_SLIDE_UP_CLASS = "anim-slide-up";

    @FXML public VBox pollContainer;
    @FXML public ImageView mediaImageView;
    @FXML private VBox tweetRoot;
    @FXML private ImageView avatarImageView;
    @FXML private Label displayNameLabel;
    @FXML private Label usernameLabel;
    @FXML private Label dateLabel;
    @FXML private Label tweetTextLabel;
    @FXML private Button commentButton;
    @FXML private Button retweetButton;
    @FXML private Button likeButton;
    @FXML private Button bookmarkButton;
    @FXML private Button deleteButton;

    private final ClientApplicationContext context;
    private long currentLikeCount;
    private boolean liked;
    private boolean bookmarked;
    private TimelineTweet tweet;

    private Runnable onDeleteSuccess;

    public TweetItemController(ClientApplicationContext context) {
        this.context = context;
    }

    public void setOnDeleteSuccess(Runnable onDeleteSuccess) {
        this.onDeleteSuccess = onDeleteSuccess;
    }

    @FXML
    private void initialize() {
        clear();

        likeButton.setOnAction(e -> handleLike());
        retweetButton.setOnAction(e -> handleRetweet());
        commentButton.setOnAction(e -> handleReply());

        if (bookmarkButton != null) {
            bookmarkButton.setOnAction(e -> handleBookmark());
        }

        if (deleteButton != null) {
            deleteButton.setOnAction(e -> handleDelete());
        }

        playEntryAnimation();
    }

    /**
     * Fade/slide entry for a freshly added card. The starting state comes from
     * the `anim-slide-up` style class in components/animations.css.
     */
    private void playEntryAnimation() {
        if (tweetRoot == null) {
            return;
        }

        FadeTransition fade = new FadeTransition(Duration.millis(220), tweetRoot);
        fade.setFromValue(0);
        fade.setToValue(1);

        TranslateTransition slide = new TranslateTransition(Duration.millis(220), tweetRoot);
        slide.setFromY(16);
        slide.setToY(0);

        ParallelTransition entry = new ParallelTransition(fade, slide);

        // `anim-slide-up` pins -fx-opacity and -fx-translate-y as the animation start
        // state. JavaFX re-applies CSS-declared styleable properties on every CSS pass,
        // so leaving the class on the node undoes the entry animation the next time any
        // re-styling happens for another reason (e.g. :hover, theme toggle). Remove it
        // immediately so the rendered card stays visible for the rest of its lifetime.
        entry.setOnFinished(event -> tweetRoot.getStyleClass().remove(ANIM_SLIDE_UP_CLASS));

        entry.play();
    }

    /**
     * Pre-loads the avatar so the card is never shown empty on first hover.
     * Kept in sync with the existing setAvatar() path.
     */
    public void preloadAvatar(String avatarUrl) {
        if (avatarUrl != null && !avatarUrl.isBlank()) {
            AvatarLoader.loadAvatar(avatarImageView, avatarUrl);
        }
        else {
            AvatarLoader.loadDefaultAvatar(avatarImageView);
        }
    }

    public void setTweet(TimelineTweet tweet) {
        this.tweet = tweet;
        if (tweet == null) {
            clear();
            return;
        }

        this.currentLikeCount = tweet.likeCount();

        displayNameLabel.setText(nullSafe(tweet.displayName()));
        usernameLabel.setText(tweet.username() == null ? "" : "@" + tweet.username());
        dateLabel.setText(formatDate(tweet.publishedAt()));
        tweetTextLabel.setText(nullSafe(tweet.content()));

        commentButton.setText("💬 " + tweet.replyCount());
        retweetButton.setText("🔁 " + tweet.retweetCount());
        updateLikeButton();
        setAvatar(tweet.avatarUrl());
        checkDeletePermission();

        loadLikeState();
        loadBookmarkState();
    }


    private void loadLikeState() {
        likeButton.setDisable(true);
        if (tweet == null)
            return;

        likeButton.setDisable(true);

        context.getUserClientService()
                .isLike(tweet.tweetId())
                .thenAccept(result -> Platform.runLater(() -> {

                    likeButton.setDisable(false);

                    if (result.isFailure())
                        return;

                    liked = result.getData().liked();
                    updateLikeButton();

                }))
                .exceptionally(error -> {

                    Platform.runLater(() -> {
                        likeButton.setDisable(false);
                    });

                    return null;
                });
    }

    private void loadBookmarkState() {
        if (bookmarkButton == null || tweet == null) {
            return;
        }

        bookmarkButton.setDisable(true);

        context.getBookmarkClientService()
                .isBookmarked(tweet.tweetId())
                .thenAccept(result -> Platform.runLater(() -> {

                    bookmarkButton.setDisable(false);

                    if (result == null || result.isFailure() || result.getData() == null) {
                        return;
                    }

                    bookmarked = result.getData().bookmarked();
                    updateBookmarkButton();
                }))
                .exceptionally(error -> {
                    Platform.runLater(() -> bookmarkButton.setDisable(false));
                    return null;
                });
    }

    /**
     * Toggles the bookmark optimistically, exactly like the like action, and
     * rolls back when the backend rejects the change.
     */
    private void handleBookmark() {
        if (tweet == null) {
            return;
        }

        bookmarkButton.setDisable(true);

        boolean oldState = bookmarked;
        bookmarked = !bookmarked;
        updateBookmarkButton();

        var future = bookmarked
                ? context.getBookmarkClientService().bookmark(tweet.tweetId())
                : context.getBookmarkClientService().unbookmark(tweet.tweetId());

        future.thenAccept(result -> Platform.runLater(() -> {

            bookmarkButton.setDisable(false);

            if (result == null || result.isFailure()) {
                bookmarked = oldState;
                updateBookmarkButton();
            }
        }))
        .exceptionally(error -> {
            Platform.runLater(() -> {
                bookmarkButton.setDisable(false);
                bookmarked = oldState;
                updateBookmarkButton();
            });

            return null;
        });
    }

    private void updateBookmarkButton() {
        if (bookmarkButton == null) {
            return;
        }

        bookmarkButton.getStyleClass().remove("tweet-action-btn-active");

        if (bookmarked) {
            bookmarkButton.getStyleClass().add("tweet-action-btn-active");
        }
    }

    private void checkDeletePermission() {
        if (deleteButton == null || tweet == null) return;

        UUID currentUserId = context.session().getCurrentUserId();
        boolean isOwner = currentUserId != null && currentUserId.equals(tweet.authorId());

        deleteButton.setVisible(isOwner);
        deleteButton.setManaged(isOwner);
    }

    private void clear() {
        displayNameLabel.setText("");
        usernameLabel.setText("");
        dateLabel.setText("");
        tweetTextLabel.setText("");
        commentButton.setText("💬 0");
        retweetButton.setText("🔁 0");

        if (deleteButton != null) {
            deleteButton.setVisible(false);
            deleteButton.setManaged(false);
        }

        if (bookmarkButton != null) {
            bookmarkButton.setDisable(false);
        }

        liked = false;
        bookmarked = false;
        currentLikeCount = 0;
        updateLikeButton();
        updateBookmarkButton();
        AvatarLoader.loadDefaultAvatar(avatarImageView);
    }

    /**
     * Node property key under which a card node stores its controller, so a view
     * can refresh the cards it already rendered without rebuilding its list.
     */
    public static final String NODE_KEY = "tweetItemController";

    /**
     * Re-applies the live current-user avatar to this card.
     *
     * Only cards authored by the signed-in user are touched; every other card
     * keeps the avatar that arrived with its tweet. This is how an avatar change
     * reaches cards that were rendered before the change, without a reload.
     */
    public void applyCurrentUserAvatar(String avatarUrl) {
        if (tweet == null || avatarUrl == null || avatarUrl.isBlank()) {
            return;
        }

        UUID currentUserId = context.session().getCurrentUserId();

        if (currentUserId == null || !currentUserId.equals(tweet.authorId())) {
            return;
        }

        setAvatar(avatarUrl);
    }

    private void setAvatar(String avatarUrl) {
        AvatarLoader.loadAvatar(avatarImageView, avatarUrl);
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private String formatDate(OffsetDateTime dateTime) {
        if (dateTime == null) {
            return "";
        }

        try {
            return dateTime.format(
                    DateTimeFormatter.ofLocalizedDateTime(
                            FormatStyle.MEDIUM,
                            FormatStyle.SHORT
                    ).withLocale(Locale.getDefault())
            );
        } catch (Exception e) {
            return dateTime.toString();
        }
    }

    private void handleLike() {

        if (tweet == null)
            return;

        likeButton.setDisable(true);

        boolean oldLiked = liked;
        long oldLikeCount = currentLikeCount;

        context.getTweetService()
                .likeTweet(tweet.tweetId())
                .thenAccept(result -> Platform.runLater(() -> {

                    if (result.isFailure()) {

                        likeButton.setDisable(false);

                        liked = oldLiked;
                        currentLikeCount = oldLikeCount;
                        updateLikeButton();
                        return;
                    }


                    LikeResponse response = result.getData();
                    if (response != null) {
                        liked = response.liked();
                        currentLikeCount = response.totalLikesCount();
                    }
                    likeButton.setDisable(false);
                    updateLikeButton();

                }))
                .exceptionally(error -> {

                    Platform.runLater(() -> {

                        likeButton.setDisable(false);

                        liked = oldLiked;
                        currentLikeCount = oldLikeCount;

                        updateLikeButton();
                    });

                    return null;
                });
    }

    private void updateLikeButton() {

        likeButton.setText("❤ " + currentLikeCount);

    }

    private void handleRetweet() {
        if (tweet == null) return;

        retweetButton.setDisable(true);

        context.getTweetService().retweet(tweet.tweetId(), null)
                .thenAccept(result -> Platform.runLater(() -> {
                    retweetButton.setDisable(false);

                    if (result.isFailure()) return;

                    long newCount = tweet.retweetCount() + 1;
                    retweetButton.setText("🔁 " + newCount);
                }))
                .exceptionally(error -> {
                    Platform.runLater(() -> retweetButton.setDisable(false));
                    return null;
                });
    }

    private void handleDelete() {
        if (tweet == null) return;

        deleteButton.setDisable(true);

        context.getTweetService().deleteTweet(tweet.tweetId())
                .thenAccept(result -> Platform.runLater(() -> {
                    deleteButton.setDisable(false);

                    if (result.isFailure()) {
                        log.warning("Failed to delete tweet: " + result.getError());
                        return;
                    }

                    log.info("Tweet deleted successfully on server!");

                    if (onDeleteSuccess != null) {
                        onDeleteSuccess.run();
                    }
                }))
                .exceptionally(error -> {
                    Platform.runLater(() -> deleteButton.setDisable(false));
                    return null;
                });
    }

    private void handleReply() {
        if (tweet == null) {
            return;
        }

        try {
            FXMLLoader loader =
                    new FXMLLoader(getClass().getResource("/Client/fxml/Comment.fxml"));

            Parent root = loader.load();

            CommentController controller = loader.getController();
            controller.setDialogData(context, tweet);

            Stage stage = new Stage();
            stage.setTitle("Reply");
            stage.initModality(Modality.APPLICATION_MODAL);
            Scene dialogScene = new Scene(root);
            context.getThemeManager().attach(dialogScene);
            stage.setScene(dialogScene);
            stage.showAndWait();

        } catch (Exception e) {
            log.severe(e.getMessage());
        }
    }
}