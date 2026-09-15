package Client.controllers;

import Client.ClientApplicationContext;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import logic_core.app.dto.response.ConversationMessagesResponse;
import logic_core.app.dto.response.ConversationSummaryResponse;
import logic_core.app.dto.response.GetConversationsResponse;
import logic_core.app.dto.response.MessageInfoResponse;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MessagesController {

    private static final Logger log = Logger.getLogger(MessagesController.class.getName());

    private static final int CONVERSATIONS_PAGE = 1;
    private static final int CONVERSATIONS_PAGE_SIZE = 50;

    private static final int MESSAGES_PAGE = 1;
    private static final int MESSAGES_PAGE_SIZE = 100;


    @FXML
    private VBox chatsContainer;

    @FXML
    private Label currentChatUserLabel;

    @FXML
    private VBox messagesContainer;

    @FXML
    private TextField messageInputField;

    @FXML
    private Button sendMessageButton;

    /** Guards against duplicate submits while a send request is in flight. */
    private boolean sendInFlight;

    private final ClientApplicationContext context;

    private UUID selectedChatId;

    private final Object sendLock = new Object();

    private final AtomicLong conversationsRequestVersion =
            new AtomicLong(0);

    private final AtomicLong messagesRequestVersion =
            new AtomicLong(0);

    private final AtomicLong sendRequestVersion =
            new AtomicLong(0);


    public MessagesController(
            ClientApplicationContext context) {
        this.context = context;
    }

    @FXML
    public void initialize() {

        updateSendState();

        messageInputField.textProperty().addListener(
                (observable, oldValue, newValue) -> updateSendState());

        messageInputField.setOnKeyPressed(event -> {

            if (event.getCode() == KeyCode.ENTER) {
                handleSendMessage(null);
                event.consume();
            }
        });




        if (!context.session().isLoggedIn()) {

            showMessagesPlaceholder("Please log in to view your messages.");

            return;
        }

        UUID pendingRecipient = consumePendingRecipient();

        if (pendingRecipient != null) {

            // Opened from a profile's "Message" action: refresh the conversation
            // list without auto-selecting, then open the requested conversation.
            loadUserConversationsWithoutAutoSelection();

            openConversationWith(List.of(pendingRecipient));

            return;
        }

        loadUserConversations();
    }

    /**
     * Reads (and clears) the recipient the shell stored for this mount, if any.
     */
    private UUID consumePendingRecipient() {

        MainLayoutController shell = context.getMainLayoutController();

        return shell == null ? null : shell.consumePendingMessageRecipient();
    }

    public void openConversationWith(List<UUID> recipientIds) {

        if (recipientIds == null || recipientIds.isEmpty()) {
            return;
        }

        UUID currentUserId = context.session().getCurrentUserId();

        context.getConversationClientService()
                .createConversation(
                        currentUserId,
                        recipientIds
                )
                .thenAccept(result -> {

                    if (result != null && result.isSuccess() && result.getData() != null) {
                        UUID conversationId = result.getData().conversationId();

                        Platform.runLater(() -> selectConversationByConversationId(conversationId));

                        return;
                    }

                    log.warning("Failed to open conversation.");
                })
                .exceptionally(error -> {

                    log.log(Level.SEVERE,
                            "Failed to create conversation",
                            error);

                    return null;
                });
    }

    // =========================================================
    // CONVERSATIONS
    // =========================================================

    private void loadUserConversations() {

        long requestVersion = conversationsRequestVersion.incrementAndGet();

        showChatsPlaceholder("Loading conversations...");

        context.getConversationClientService()
                .getConversations(
                        CONVERSATIONS_PAGE,
                        CONVERSATIONS_PAGE_SIZE
                )
                .thenAccept(result ->
                        Platform.runLater(() -> {

                            if (requestVersion != conversationsRequestVersion.get()) {
                                return;
                            }

                            if (result == null || result.isFailure()) {

                                String error =
                                        result == null
                                                ? "UNKNOWN_ERROR"
                                                : result.getError();

                                log.warning("Failed to load conversations: " + error);

                                showChatsPlaceholder("Failed to load conversations.");

                                return;
                            }

                            GetConversationsResponse response = result.getData();

                            if (response == null
                                    || response.conversations() == null
                                    || response.conversations().isEmpty()) {

                                showChatsPlaceholder("No conversations yet.");

                                currentChatUserLabel.setText("Select a chat");

                                messagesContainer.getChildren().clear();

                                updateSendState();

                                return;
                            }

                            renderConversations(response.conversations());

                            ConversationSummaryResponse first =
                                    response.conversations().get(0);

                            selectConversation(first);
                        })
                )
                .exceptionally(error -> {

                    log.log(Level.SEVERE,
                            "Critical error loading conversations",
                            error);

                    Platform.runLater(() ->
                            showChatsPlaceholder("Connection error."));

                    return null;
                });
    }

    private void selectConversationByConversationId(UUID conversationId) {

        if (conversationId == null) {
            return;
        }

        ConversationSummaryResponse summary = ConversationSummaryResponse.builder()
                .conversationId(conversationId)
                .title("Conversation")
                .lastMessage("")
                .unreadCount(0)
                .build();

        selectConversation(summary);
    }

    private void renderConversations(
            List<ConversationSummaryResponse> conversations) {

        chatsContainer.getChildren().clear();

        for (ConversationSummaryResponse conversation : conversations) {

            if (conversation == null
                    || conversation.conversationId() == null) {
                continue;
            }

            chatsContainer.getChildren().add(
                    createConversationCard(conversation)
            );
        }
    }

    private VBox createConversationCard(
            ConversationSummaryResponse conversation) {

        Label titleLabel =
                new Label(
                        safeText(
                                conversation.title(),
                                "Conversation"
                        )
                );

        titleLabel.getStyleClass().add("conversation-title");

        Label previewLabel =
                new Label(
                        safeText(
                                conversation.lastMessage(),
                                ""
                        )
                );

        previewLabel.setWrapText(true);
        previewLabel.setMaxWidth(190);
        previewLabel.getStyleClass().add("conversation-preview");

        VBox textContainer =
                new VBox(
                        3,
                        titleLabel,
                        previewLabel
                );

        HBox.setHgrow(
                textContainer,
                Priority.ALWAYS
        );

        Label unreadLabel = new Label();

        if (conversation.unreadCount() > 0) {

            unreadLabel.setText(
                    String.valueOf(
                            conversation.unreadCount()
                    )
            );

            unreadLabel.getStyleClass().add("conversation-unread-badge");
        }

        HBox card =
                new HBox(
                        10,
                        textContainer,
                        unreadLabel
                );

        card.setMaxWidth(Double.MAX_VALUE);

        card.getStyleClass().add("conversation-card");

        if (conversation.conversationId().equals(selectedChatId)) {

            card.getStyleClass().add("conversation-card-active");
        }

        VBox wrapper = new VBox(card);
        wrapper.setMaxWidth(Double.MAX_VALUE);

        card.setOnMouseClicked(e ->
                selectConversation(conversation));

        return wrapper;
    }

    private void selectConversation(ConversationSummaryResponse conversation) {

        if (conversation == null
                || conversation.conversationId() == null) {
            return;
        }

        selectedChatId = conversation.conversationId();

        currentChatUserLabel.setText(
                safeText(
                        conversation.title(),
                        "Conversation"
                )
        );

        updateSendState();

        loadUserConversationsWithoutAutoSelection();

        loadConversationMessages(selectedChatId);
    }

    private void loadUserConversationsWithoutAutoSelection() {

        long requestVersion = conversationsRequestVersion.incrementAndGet();

        context.getConversationClientService()
                .getConversations(
                        CONVERSATIONS_PAGE,
                        CONVERSATIONS_PAGE_SIZE
                )
                .thenAccept(result ->
                        Platform.runLater(() -> {

                            if (requestVersion != conversationsRequestVersion.get()) {
                                return;
                            }

                            if (result == null || result.isFailure()) {
                                return;
                            }

                            GetConversationsResponse response = result.getData();

                            if (response == null || response.conversations() == null) {
                                return;
                            }

                            renderConversations(response.conversations());
                        })
                )
                .exceptionally(error -> {

                    log.log(
                            Level.WARNING,
                            "Failed to refresh conversation list",
                            error
                    );

                    return null;
                });
    }



    // =========================================================
    // MESSAGES
    // =========================================================

    // =========================================================
// MESSAGES
// =========================================================

    private void loadConversationMessages(UUID conversationId) {

        if (conversationId == null) {
            return;
        }

        long requestVersion = messagesRequestVersion.incrementAndGet();

        showMessagesPlaceholder("Loading messages...");

        context.getMessageClientService()
                .getConversationMessages(
                        conversationId,
                        MESSAGES_PAGE,
                        MESSAGES_PAGE_SIZE
                )
                .thenAccept(result ->
                        Platform.runLater(() -> {

                            if (requestVersion != messagesRequestVersion.get()) {
                                return;
                            }

                            if (!conversationId.equals(selectedChatId)) {
                                return;
                            }

                            if (result == null || result.isFailure()) {

                                log.warning(
                                        "Failed to load messages: " +
                                                (result == null ? "UNKNOWN" : result.getError())
                                );

                                showMessagesPlaceholder("Failed to load messages.");
                                return;
                            }

                            ConversationMessagesResponse response = result.getData();

                            if (response == null
                                    || response.messages() == null
                                    || response.messages().isEmpty()) {

                                showMessagesPlaceholder("No messages yet.");
                                return;
                            }

                            renderMessages(response.messages());
                        }))
                .exceptionally(error -> {

                    log.log(Level.SEVERE, "Load messages error", error);

                    Platform.runLater(() ->
                            showMessagesPlaceholder("Connection error."));

                    return null;
                });
    }


    private void renderMessages(List<MessageInfoResponse> messages) {

        messagesContainer.getChildren().clear();

        UUID currentUserId = context.session().getCurrentUserId();

        for (MessageInfoResponse message : messages) {

            if (message == null) {
                continue;
            }

            boolean mine =
                    currentUserId != null
                            && currentUserId.equals(message.senderId());

            messagesContainer.getChildren().add(
                    createMessageBubble(message, mine)
            );
        }
    }


    private HBox createMessageBubble(
            MessageInfoResponse message,
            boolean mine) {

        Label contentLabel =
                new Label(safeText(message.content(), ""));

        contentLabel.setWrapText(true);
        contentLabel.setMaxWidth(450);

        String time = "";

        if (message.createdAt() != null) {
            time = message.createdAt()
                    .format(DateTimeFormatter.ofPattern("HH:mm"));
        }

        Label timeLabel = new Label(time);

        timeLabel.getStyleClass().add("message-timestamp");

        VBox bubbleContent =
                new VBox(
                        3,
                        contentLabel,
                        timeLabel
                );

        bubbleContent.setMaxWidth(470);

        if (message.edited()) {

            Label edited =
                    new Label("edited");

            edited.getStyleClass().add("message-edited");

            bubbleContent.getChildren().add(edited);
        }

        HBox bubble =
                new HBox(bubbleContent);

        bubble.setMaxWidth(Double.MAX_VALUE);

        if (mine) {

            bubble.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
            contentLabel.getStyleClass().add("message-bubble-sent");

        } else {

            bubble.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
            contentLabel.getStyleClass().add("message-bubble-received");
        }

        return bubble;
    }


    // =========================================================
    // SEND MESSAGE
    // =========================================================

    @FXML
    void handleSendMessage(
            ActionEvent event) {

        UUID conversationId;

        synchronized (sendLock) {
            if (selectedChatId == null) {

                log.warning("No conversation selected.");

                return;
            }

            conversationId = selectedChatId;
        }

        String content = messageInputField.getText();

        if (content == null || content.isBlank()) {
            return;
        }

        String trimmedContent = content.trim();

        if (trimmedContent.length() > 1000) {

            showMessagesPlaceholder("Message cannot exceed 1000 characters.");

            return;
        }

        long requestVersion = sendRequestVersion.incrementAndGet();

        setSendInFlight(true);


        context.getMessageClientService()
                .sendMessage(
                        conversationId,
                        trimmedContent
                )

                .thenAccept(result ->
                        Platform.runLater(() -> {

                            if (requestVersion != sendRequestVersion.get()) {
                                return;
                            }


                            if (result == null || result.isFailure()) {

                                String error =
                                        result == null
                                                ? "UNKNOWN_ERROR"
                                                : result.getError();

                                log.warning("Message send failed: " + error);


                                setSendInFlight(false);

                                return;
                            }


                            messageInputField.clear();


                            loadConversationMessages(
                                    conversationId
                            );


                            loadUserConversationsWithoutAutoSelection();


                            setSendInFlight(false);

                            messageInputField.requestFocus();
                        })
                )

                .exceptionally(error -> {

                    log.log(
                            Level.SEVERE,
                            "Critical error sending message",
                            error
                    );

                    Platform.runLater(() -> setSendInFlight(false));

                    return null;
                });
    }


    // =========================================================
    // UI HELPERS
    // =========================================================

    /**
     * Send is only available when a conversation is selected, the composer holds
     * non-blank text, and no send request is currently in flight.
     *
     * The text field itself is never disabled: typing must stay possible both
     * before a conversation is picked and while a send is running.
     */
    private void updateSendState() {

        if (sendMessageButton == null) {
            return;
        }

        String text = messageInputField == null ? null : messageInputField.getText();

        boolean hasText = text != null && !text.isBlank();

        sendMessageButton.setDisable(sendInFlight || selectedChatId == null || !hasText);
    }

    private void setSendInFlight(boolean value) {

        sendInFlight = value;

        updateSendState();
    }

    private void showChatsPlaceholder(String message) {

        chatsContainer.getChildren().clear();


        Label label = new Label(message);


        label.setWrapText(true);


        label.getStyleClass().add("empty-state");


        chatsContainer.getChildren().add(label);
    }


    private void showMessagesPlaceholder(
            String message) {

        messagesContainer.getChildren().clear();

        Label label = new Label(message);
        label.setWrapText(true);
        label.getStyleClass().add("empty-state");

        messagesContainer.getChildren().add(label);
    }


    private String safeText(String value, String fallback) {

        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }
}