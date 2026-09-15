package Client.controllers;

import Client.AvatarLoader;
import Client.ClientApplicationContext;
import Client.navigation.NavigationRoute;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.util.Duration;
import logic_core.app.dto.response.ConversationSummaryResponse;
import logic_core.app.dto.response.UserSummaryResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Animated widget for the utility panel.
 *
 * It crossfades between two scenes on a slow timer:
 * <ol>
 *   <li>people the current user follows</li>
 *   <li>the user's most recent conversations</li>
 * </ol>
 *
 * Both scenes are built from real backend responses through the existing
 * client services - nothing is mocked. When a source has no data (or the
 * request fails) the scene shows the shared empty/error state instead. The
 * rotation pauses automatically while the widget is not on screen, so it never
 * animates in the background.
 */
public class DynamicWidgetController
{
    private static final Logger log = Logger.getLogger(DynamicWidgetController.class.getName());

    private static final int SCENE_COUNT = 2;
    private static final int ROW_LIMIT = 3;
    private static final Duration ROTATION_INTERVAL = Duration.seconds(10);
    private static final Duration FADE_DURATION = Duration.millis(220);

    private static final String[] SCENE_TITLES = {
            "People you follow",
            "Recent conversations"
    };

    @FXML
    private VBox widgetRoot;

    @FXML
    private Label widgetTitle;

    @FXML
    private VBox widgetBody;

    @FXML
    private HBox widgetDots;

    private final ClientApplicationContext context;

    private final List<Region> dots = new ArrayList<>();
    private final VBox[] scenes = new VBox[SCENE_COUNT];

    private int activeScene = -1;
    private Timeline rotation;

    public DynamicWidgetController(ClientApplicationContext context)
    {
        this.context = context;
    }

    @FXML
    private void initialize()
    {
        buildDots();

        scenes[0] = messageBlock("Loading people you follow...");
        scenes[1] = messageBlock("Loading conversations...");

        showScene(0, false);

        loadFollowings();
        loadConversations();

        startRotation();
    }

    /**
     * Re-fetches both scenes.
     *
     * The widget is mounted once with the shell, so without this the follow
     * graph would be frozen at whatever it looked like when the shell mounted.
     * Called by the shell after the current user follows or unfollows someone.
     */
    public void reload()
    {
        loadFollowings();
        loadConversations();
    }

    // =========================================================
    // SCENE ROTATION
    // =========================================================

    private void startRotation()
    {
        rotation = new Timeline(new KeyFrame(ROTATION_INTERVAL, event ->
        {
            // Never animate while the widget is off screen.
            if (isShowing())
            {
                showScene((activeScene + 1) % SCENE_COUNT, true);
            }
        }));

        rotation.setCycleCount(Animation.INDEFINITE);
        rotation.play();
    }

    private void showScene(int index, boolean animate)
    {
        if (widgetBody == null)
        {
            return;
        }

        if (index < 0 || index >= SCENE_COUNT)
        {
            return;
        }

        if (animate && index == activeScene)
        {
            return;
        }

        activeScene = index;
        updateDots(index);

        if (widgetTitle != null)
        {
            widgetTitle.setText(SCENE_TITLES[index]);
        }

        Node content = scenes[index];

        if (content == null)
        {
            return;
        }

        if (!animate)
        {
            content.setOpacity(1);
            widgetBody.getChildren().setAll(content);
            return;
        }

        FadeTransition fadeOut = new FadeTransition(FADE_DURATION, widgetBody);
        fadeOut.setFromValue(1);
        fadeOut.setToValue(0);
        fadeOut.setOnFinished(event ->
        {
            content.setOpacity(1);
            widgetBody.getChildren().setAll(content);

            FadeTransition fadeIn = new FadeTransition(FADE_DURATION, widgetBody);
            fadeIn.setFromValue(0);
            fadeIn.setToValue(1);
            fadeIn.play();
        });
        fadeOut.play();
    }

    private void buildDots()
    {
        if (widgetDots == null)
        {
            return;
        }

        widgetDots.getChildren().clear();
        dots.clear();

        for (int i = 0; i < SCENE_COUNT; i++)
        {
            Region dot = new Region();
            dot.getStyleClass().add("widget-dot");
            dots.add(dot);
            widgetDots.getChildren().add(dot);
        }
    }

    private void updateDots(int activeIndex)
    {
        for (int i = 0; i < dots.size(); i++)
        {
            Region dot = dots.get(i);
            dot.getStyleClass().remove("widget-dot-active");

            if (i == activeIndex)
            {
                dot.getStyleClass().add("widget-dot-active");
            }
        }
    }

    /** Re-renders a scene that has just finished loading. */
    private void refreshScene(int index)
    {
        if (widgetBody == null)
        {
            return;
        }

        if (index < 0 || index >= SCENE_COUNT || scenes[index] == null)
        {
            return;
        }

        if (index == activeScene)
        {
            widgetBody.getChildren().setAll(scenes[index]);
            return;
        }

        activeScene = index;
        updateDots(index);

        if (widgetTitle != null)
        {
            widgetTitle.setText(SCENE_TITLES[index]);
        }

        widgetBody.getChildren().setAll(scenes[index]);
    }

    private boolean isShowing()
    {
        return widgetRoot != null
                && widgetRoot.getScene() != null
                && widgetRoot.getScene().getWindow() != null
                && widgetRoot.getScene().getWindow().isShowing();
    }

    // =========================================================
    // SCENE 1 - PEOPLE THE USER FOLLOWS
    // =========================================================

    private void loadFollowings()
    {
        UUID currentUserId = currentUserId();

        if (currentUserId == null)
        {
            scenes[0] = messageBlock("Sign in to see people you follow.");
            refreshScene(0);
            return;
        }

        context.getFollowQueryClientService()
                .getFollowings(currentUserId)
                .thenAccept(result ->
                {
                    // A failed request is not an empty follow list: surface it
                    // instead of rendering the "not following anyone yet" state.
                    if (result == null || result.isFailure() || result.getData() == null)
                    {
                        String error = result == null ? "UNKNOWN_ERROR" : result.getError();

                        log.warning("Failed to load widget followings: " + error);

                        Platform.runLater(() ->
                        {
                            scenes[0] = errorBlock("Could not load the people you follow.");
                            refreshScene(0);
                        });

                        return;
                    }

                    List<UserSummaryResponse> users = result.getData().users();

                    Platform.runLater(() ->
                    {
                        if (users == null || users.isEmpty())
                        {
                            scenes[0] = messageBlock("You are not following anyone yet.");
                        }
                        else
                        {
                            scenes[0] = buildPeopleScene(users);
                        }

                        refreshScene(0);
                    });
                })
                .exceptionally(error ->
                {
                    log.warning("Failed to load widget followings: " + error.getMessage());

                    Platform.runLater(() ->
                    {
                        scenes[0] = errorBlock("Could not load the people you follow.");
                        refreshScene(0);
                    });

                    return null;
                });
    }

    private VBox buildPeopleScene(List<UserSummaryResponse> users)
    {
        VBox box = newBody();

        if (users == null || users.isEmpty())
        {
            box.getChildren().add(emptyLabel("You are not following anyone yet."));
            return box;
        }

        int shown = 0;

        for (UserSummaryResponse user : users)
        {
            if (shown++ >= ROW_LIMIT)
            {
                break;
            }

            box.getChildren().add(personRow(user));
        }

        return box;
    }

    private HBox personRow(UserSummaryResponse user)
    {
        HBox row = new HBox();
        row.getStyleClass().add("widget-row");

        ImageView avatar = new ImageView();
        avatar.setFitWidth(36);
        avatar.setFitHeight(36);
        avatar.setPreserveRatio(true);
        avatar.setPickOnBounds(true);
        avatar.setClip(new Circle(18, 18, 18));
        AvatarLoader.loadAvatar(avatar, user.avatarUrl());

        VBox names = new VBox();
        names.getStyleClass().add("widget-row-names");

        Label name = new Label(safe(user.displayName()));
        name.getStyleClass().add("widget-row-name");

        Label handle = new Label("@" + safe(user.username()));
        handle.getStyleClass().add("widget-row-handle");

        names.getChildren().addAll(name, handle);
        HBox.setHgrow(names, Priority.ALWAYS);

        row.getChildren().addAll(avatar, names);
        row.setOnMouseClicked(event -> openProfile());

        return row;
    }

    // =========================================================
    // SCENE 2 - RECENT CONVERSATIONS
    // =========================================================

    private void loadConversations()
    {
        if (currentUserId() == null)
        {
            scenes[1] = messageBlock("Sign in to see your conversations.");
            refreshScene(1);
            return;
        }

        context.getConversationClientService()
                .getConversations(0, ROW_LIMIT)
                .thenAccept(result ->
                {
                    // Same rule as the followings scene: a failure must not be
                    // rendered as "no conversations yet".
                    if (result == null || result.isFailure() || result.getData() == null)
                    {
                        String error = result == null ? "UNKNOWN_ERROR" : result.getError();

                        log.warning("Failed to load widget conversations: " + error);

                        Platform.runLater(() ->
                        {
                            scenes[1] = errorBlock("Could not load your conversations.");
                            refreshScene(1);
                        });

                        return;
                    }

                    List<ConversationSummaryResponse> conversations =
                            result.getData().conversations();

                    Platform.runLater(() ->
                    {
                        if (conversations == null || conversations.isEmpty())
                        {
                            scenes[1] = messageBlock("No conversations yet.");
                        }
                        else
                        {
                            scenes[1] = buildConversationsScene(conversations);
                        }

                        refreshScene(1);
                    });
                })
                .exceptionally(error ->
                {
                    log.warning("Failed to load widget conversations: " + error.getMessage());

                    Platform.runLater(() ->
                    {
                        scenes[1] = errorBlock("Could not load your conversations.");
                        refreshScene(1);
                    });

                    return null;
                });
    }

    private VBox buildConversationsScene(List<ConversationSummaryResponse> conversations)
    {
        VBox box = newBody();

        if (conversations == null || conversations.isEmpty())
        {
            box.getChildren().add(emptyLabel("No conversations yet."));
            return box;
        }

        int shown = 0;

        for (ConversationSummaryResponse conversation : conversations)
        {
            if (shown++ >= ROW_LIMIT)
            {
                break;
            }

            box.getChildren().add(conversationRow(conversation));
        }

        return box;
    }

    private HBox conversationRow(ConversationSummaryResponse conversation)
    {
        HBox row = new HBox();
        row.getStyleClass().add("widget-row");

        VBox texts = new VBox();
        texts.getStyleClass().add("widget-row-names");

        Label title = new Label(safe(conversation.title()));
        title.getStyleClass().add("widget-row-title");

        Label lastMessage = new Label(safe(conversation.lastMessage()));
        lastMessage.getStyleClass().add("widget-row-message");

        texts.getChildren().addAll(title, lastMessage);
        HBox.setHgrow(texts, Priority.ALWAYS);

        row.getChildren().add(texts);

        // The badge is only added when the backend reports unread messages.
        if (conversation.unreadCount() > 0)
        {
            Label badge = new Label(String.valueOf(conversation.unreadCount()));
            badge.getStyleClass().add("badge");
            row.getChildren().add(badge);
        }

        row.setOnMouseClicked(event -> openMessages());

        return row;
    }

    // =========================================================
    // HELPERS
    // =========================================================

    private VBox newBody()
    {
        VBox box = new VBox();
        box.getStyleClass().add("widget-scene-body");
        return box;
    }

    private VBox messageBlock(String message)
    {
        VBox box = newBody();
        box.getChildren().add(emptyLabel(message));
        return box;
    }

    /**
     * Renders a failed load with the shared error-state class, so an error is
     * never mistaken for an empty list.
     */
    private VBox errorBlock(String message)
    {
        VBox box = newBody();

        Label label = new Label(message);
        label.getStyleClass().add("error-state");
        label.setWrapText(true);

        box.getChildren().add(label);

        return box;
    }

    private Label emptyLabel(String message)
    {
        Label label = new Label(message);
        label.getStyleClass().add("widget-empty");
        label.setWrapText(true);
        return label;
    }

    private UUID currentUserId()
    {
        return context.session().isLoggedIn() ? context.getSnapshot().userId() : null;
    }

    private void openProfile()
    {
        if (context.navigation() != null)
        {
            context.navigation().navigate(NavigationRoute.PROFILE);
        }
    }

    private void openMessages()
    {
        if (context.navigation() != null)
        {
            context.navigation().navigate(NavigationRoute.MESSAGES);
        }
    }

    private String safe(String value)
    {
        return value == null ? "" : value;
    }
}
