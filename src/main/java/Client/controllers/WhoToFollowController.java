package Client.controllers;

import Client.AvatarLoader;
import Client.ClientApplicationContext;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import logic_core.app.dto.response.UserSummaryResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * "Who to follow" card for the left sidebar.
 *
 * Mirrors the widget row styling used by DynamicWidget, but shows a single
 * static placeholder list since there is no backend use case for suggested
 * users yet. Avatar loading is real (uses AvatarLoader), but the user list
 * is illustrative until the suggestion service exists.
 */
public class WhoToFollowController
{
    private static final Logger log = Logger.getLogger(WhoToFollowController.class.getName());

    private static final int ROW_LIMIT = 3;

    @FXML
    private VBox cardRoot;

    private final ClientApplicationContext context;

    public WhoToFollowController(ClientApplicationContext context)
    {
        this.context = context;
    }

    @FXML
    private void initialize()
    {
        if (context.session().isLoggedIn())
        {
            buildPlaceholderScene();
        }
        else
        {
            cardRoot.getChildren().setAll(messageBlock("Sign in to see suggestions."));
        }
    }

    private void buildPlaceholderScene()
    {
        List<UserSummaryResponse> suggestions = placeholderUsers();

        if (suggestions.isEmpty())
        {
            cardRoot.getChildren().setAll(messageBlock("No suggestions available."));
            return;
        }

        VBox body = newBody();
        int shown = 0;

        for (UserSummaryResponse user : suggestions)
        {
            if (shown++ >= ROW_LIMIT)
            {
                break;
            }

            body.getChildren().add(personRow(user));
        }

        cardRoot.getChildren().setAll(body);
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

        Button followButton = new Button("Follow");
        followButton.getStyleClass().add("widget-row-follow");
        followButton.setOnAction(this::handleFollowClick);

        row.getChildren().addAll(avatar, names, followButton);
        row.setOnMouseClicked(event -> openProfile());

        return row;
    }

    @FXML
    private void handleFollowClick(ActionEvent event)
    {
        // Placeholder action until a follow use case is wired.
        log.info("Who to follow: follow click (not wired yet)");
    }

    /** Placeholder users until a suggestion use case exists. */
    private List<UserSummaryResponse> placeholderUsers()
    {
        List<UserSummaryResponse> users = new ArrayList<>();
        users.add(new UserSummaryResponse(null, "Featured suggestion", "suggest1", null, null));
        users.add(new UserSummaryResponse(null, "Another account", "account2", null, null));
        users.add(new UserSummaryResponse(null, "Explore more", "explore3", null, null));
        return users;
    }

    /**
     * Reloads the placeholder avatar for this widget after the current user
     * updates their profile picture.
     *
     * The WhoToFollow placeholder data is illustrative and intentionally
     * independent of current-user state, so this exists only to keep the
     * widget visually consistent with the rest of the shell when the
     * active theme/avatar assets change.
     */
    public void reloadCurrentUser()
    {
        // No-op placeholder: the WhoToFollow content is not derived from
        // current-user state yet, so there is nothing to refresh here.
    }

    private VBox newBody()
    {
        VBox box = new VBox();
        box.getStyleClass().add("widget-scene-body");
        return box;
    }

    private VBox messageBlock(String message)
    {
        VBox box = newBody();
        Label label = new Label(message);
        label.getStyleClass().add("widget-empty");
        label.setWrapText(true);
        box.getChildren().add(label);
        return box;
    }

    private void openProfile()
    {
        if (context.navigation() != null)
        {
            context.navigation().navigate(Client.navigation.NavigationRoute.PROFILE);
        }
    }

    private String safe(String value)
    {
        return value == null ? "" : value;
    }
}
