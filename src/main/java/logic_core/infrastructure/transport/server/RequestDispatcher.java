package logic_core.infrastructure.transport.server;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import logic_core.app.dto.request.*;
import logic_core.app.dto.response.*;
import logic_core.app.dto.timeline.TimelineTweet;
import logic_core.app.facade.*;
import logic_core.common.exception.AppException;
import logic_core.common.result.Result;
import logic_core.infrastructure.transport.RequestEnvelope;
import logic_core.infrastructure.transport.RequestType;
import logic_core.infrastructure.transport.ResponseEnvelope;
import logic_core.infrastructure.transport.ResponseType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;

@RequiredArgsConstructor
@Component
public class RequestDispatcher
{
    private final Gson gson;
    private final AuthFacade authFacade;
    private final ConversationFacade conversationFacade;
    private final FollowQueryFacade followQueryFacade;
    private final MediaFacade mediaFacade;
    private final MessageFacade messageFacade;
    private final NotificationFacade notificationFacade;
    private final RelationFacade relationFacade;
    private final TimelineFacade timelineFacade;
    private final TweetFacade tweetFacade;
    private final UserFacade userFacade;

    public ResponseEnvelope dispatch(RequestEnvelope request)
    {
        Objects.requireNonNull(request, "request must not be null");

        return switch (request.type())
        {
            // ---------------- AUTH ----------------

            case AUTH_REGISTER,
                 AUTH_LOGIN,
                 AUTH_LOGOUT,
                 AUTH_REFRESH,
                 AUTH_REQUEST_PASSWORD_RESET,
                 AUTH_VERIFY_PASSWORD_RESET_CODE,
                 AUTH_RESET_PASSWORD ->

                    dispatchAuth(request);

            // ---------------- TWEET ----------------

            case TWEET_CREATE,
                 TWEET_EDIT,
                 TWEET_DELETE,
                 TWEET_REPLY,
                 TWEET_RETWEET,
                 TWEET_UNRETWEET,
                 TWEET_LIKE,
                 TWEET_UNLIKE,
                 TWEET_GET ->

                    dispatchTweet(request);

            // ---------------- TIMELINE ----------------

            case TIMELINE_GET ->

                    dispatchTimeline(request);

            // ---------------- RELATION ----------------

            case  RELATION_FOLLOW,
                  RELATION_UNFOLLOW,
                  RELATION_BLOCK,
                  RELATION_UNBLOCK,
                  RELATION_MUTE,
                  RELATION_UNMUTE ->

                    dispatchRelation(request);

            // ---------------- CONVERSATION ----------------

            case CONVERSATION_CREATE,
                 MEMBER_ADD,
                 MEMBER_DELETE,
                 CONVERSATION_DELETE,
                 CONVERSATION_GET ->

                    dispatchConversation(request);

            // ---------------- MESSAGE ----------------

            case MESSAGE_SEND,
                 MESSAGE_EDIT,
                 MESSAGE_DELETE,
                 MESSAGE_GET,
                 MESSAGE_GET_CONVERSATION ->

                    dispatchMessage(request);

            // ---------------- USER ----------------

            case USER_GET_PROFILE,
                 USER_SEARCH,
                 USER_UPDATE_PROFILE,
                 USER_UPDATE_BIO,
                 USER_UPDATE_AVATAR,
                 USER_UPDATE_BANNER,
                 USER_UPDATE_EMAIL,
                 USER_UPDATE_PASSWORD,
                 USER_DELETE_ACCOUNT,
                 USER_UPDATE_COMPLETE_PROFILE,
                 USER_GET_IS_FOLLOW,
                 USER_GET_IS_LIKE ->

                    dispatchUser(request);

            case FOLLOW_GET_FOLLOWINGS,
                 FOLLOW_GET_FOLLOWERS ->

                dispatchFollowQuery(request);

            // ------------- MEDIA   --------------

            case MEDIA_DELETE,
                 MEDIA_DOWNLOAD ->

                dispatchMedia(request);


            case TWEET_GET_REPLIES ->
                dispatchReply(request);

            // ---------------- NOTIFICATION ----------------

            case NOTIFICATION_GET,
                 NOTIFICATION_READ,
                 NOTIFICATION_READ_ALL ->

                    dispatchNotification(request);
        };
    }


    private <F> ResponseEnvelope execute(
            RequestEnvelope request,
            F facade,
            BiFunction<F, JsonElement, ResponseEnvelope> handler)
    {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(facade, "facade must not be null");
        Objects.requireNonNull(handler, "handler must not be null");

        UUID requestId = request.requestId();
        RequestType requestType = request.type();
        JsonElement payload = request.payload();

        try
        {
            return handler.apply(facade, payload);
        }
        catch (AppException e)
        {
            return failureResponse(
                    requestId,
                    responseTypeFor(requestType),
                    e.getErrorCode() != null
                            ? e.getErrorCode()
                            : "APP_ERROR",
                    e.getMessage()
            );
        }
        catch (Exception e)
        {
            return failureResponse(
                    requestId,
                    ResponseType.BAD_REQUEST,
                    "UNEXPECTED_ERROR",
                    e.getMessage() != null
                            ? e.getMessage()
                            : "Unexpected server error"
            );
        }
    }


    public ResponseEnvelope dispatchAuth(RequestEnvelope request)
    {
        return execute(
                request,
                authFacade,
                (facade, payload) ->
                {
                    UUID requestId = request.requestId();

                    return switch (request.type())
                    {
                        case AUTH_REGISTER -> handleRegister(requestId, payload, facade);

                        case AUTH_LOGIN -> handleLogin(requestId, payload, facade);

                        case AUTH_LOGOUT -> handleLogout(requestId, payload, facade);

                        case AUTH_REFRESH -> handleRefresh(requestId, payload, facade);

                        case AUTH_REQUEST_PASSWORD_RESET -> handelRequestPasswordReset(requestId, payload, facade);
                        case AUTH_VERIFY_PASSWORD_RESET_CODE -> handelVerifyPasswordResetCode(requestId, payload, facade);

                        case AUTH_RESET_PASSWORD -> handelResetPassword(requestId, payload, facade);

                        default -> throw new IllegalArgumentException("Unsupported tweet request: " + request.type());
                    };
                });
    }


    public ResponseEnvelope dispatchConversation(RequestEnvelope request)
    {
        return execute(
                request,
                conversationFacade,
                (facade, payload) ->
                {
                    UUID requestId = request.requestId();

                    return switch (request.type())
                    {
                        case CONVERSATION_CREATE -> handleCreateConversation(requestId, payload, facade);

                        case MEMBER_ADD -> handleAddMember(requestId, payload, facade);

                        case MEMBER_DELETE -> handleDeleteMember(requestId, payload, facade);

                        case CONVERSATION_GET -> handleGetConversations(requestId, payload, facade);

                        case CONVERSATION_DELETE -> handleDeleteConversation(requestId, payload, facade);

                        default -> throw new IllegalArgumentException(
                                        "Unsupported tweet request: " + request.type());
                    };
                });
    }

    public ResponseEnvelope dispatchMessage(RequestEnvelope request)
    {
        return execute(
                request,
                messageFacade,
                (facade, payload) ->
                {
                    UUID requestId = request.requestId();

                    return switch (request.type())
                    {
                        case MESSAGE_SEND -> handleSendMessage(requestId, payload, facade);

                        case MESSAGE_EDIT -> handleEditMessage(requestId, payload, facade);

                        case MESSAGE_DELETE -> handleDeleteMessage(requestId, payload, facade);

                        case MESSAGE_GET -> handleGetMessage(requestId, payload, facade);

                        case MESSAGE_GET_CONVERSATION -> handleGetConversationMessages(requestId, payload, facade);

                        default -> throw new IllegalArgumentException("Unsupported tweet request: " + request.type());
                    };
                });
    }


    public ResponseEnvelope dispatchRelation(RequestEnvelope request)
    {
        return execute(
                request,
                relationFacade,
                (facade, payload) ->
                {
                    UUID requestId = request.requestId();

                    return switch (request.type())
                    {
                        case RELATION_FOLLOW -> handleFollow(requestId, payload, facade);

                        case RELATION_UNFOLLOW -> handleUnfollow(requestId, payload, facade);

                        case RELATION_BLOCK -> handleBlock(requestId, payload, facade);

                        case RELATION_UNBLOCK -> handleUnblock(requestId, payload, facade);

                        case RELATION_MUTE -> handleMute(requestId, payload, facade);

                        case RELATION_UNMUTE -> handleUnmute(requestId, payload, facade);

                        default -> throw new IllegalArgumentException("Unsupported tweet request: " + request.type());
                    };
                });
    }


    public ResponseEnvelope dispatchTimeline(RequestEnvelope request)
    {
        return execute(
                request,
                timelineFacade,
                (facade, payload) ->
                {
                    UUID requestId = request.requestId();

                    return switch (request.type())
                    {
                        case TIMELINE_GET -> handleGetTimeline(requestId, payload, facade);

                        default -> throw new IllegalArgumentException("Unsupported tweet request: " + request.type());
                    };
                });
    }


    public ResponseEnvelope dispatchTweet(RequestEnvelope request)
    {
        return execute(
                request,
                tweetFacade,
                (facade, payload) ->
                {
                    UUID requestId = request.requestId();

                    return switch (request.type())
                    {
                        case TWEET_CREATE -> handleCreateTweet(requestId, payload, facade);

                        case TWEET_DELETE -> handleDeleteTweet(requestId, payload, facade);

                        case TWEET_EDIT -> handleEditTweet(requestId, payload, facade);

                        case TWEET_LIKE -> handleLikeTweet(requestId, payload, facade);

                        case TWEET_UNLIKE -> handleUnlikeTweet(requestId, payload, facade);

                        case TWEET_REPLY -> handleReplyTweet(requestId, payload, facade);

                        case TWEET_RETWEET -> handleRetweet(requestId, payload, facade);

                        case TWEET_UNRETWEET -> handleUnretweet(requestId, payload, facade);

                        case TWEET_GET -> handleGetTweet(requestId, payload, facade);

                        default -> throw new IllegalArgumentException("Unsupported tweet request: " + request.type());
                    };
                });
    }

    public ResponseEnvelope dispatchUser(RequestEnvelope request)
    {
        return execute(
                request,
                userFacade,
                (facade, payload) ->
                {
                    UUID requestId = request.requestId();

                    return switch (request.type())
                    {
                        case USER_GET_PROFILE -> handleGetProfile(requestId, payload, facade);

                        case USER_SEARCH -> handleSearchUsers(requestId, payload, facade);

                        case USER_UPDATE_PROFILE -> handleUpdateProfile(requestId, payload, facade);

                        case USER_UPDATE_BIO -> handleUpdateBio(requestId, payload, facade);

                        case USER_UPDATE_AVATAR -> handleUpdateAvatar(requestId, payload, facade);

                        case USER_UPDATE_BANNER -> handleUpdateBanner(requestId, payload, facade);

                        case USER_UPDATE_EMAIL -> handleUpdateEmail(requestId, payload, facade);

                        case USER_UPDATE_PASSWORD -> handleUpdatePassword(requestId, payload, facade);

                        case USER_DELETE_ACCOUNT -> handleDeleteAccount(requestId, payload, facade);

                        case USER_UPDATE_COMPLETE_PROFILE -> handleUpdateCompleteProfile(requestId,payload,facade);

                        case USER_GET_IS_FOLLOW -> handleIsFollow(requestId, payload, facade);

                        case  USER_GET_IS_LIKE -> handleIsLike(requestId, payload, facade);
                        default ->
                                throw new IllegalArgumentException(
                                        "Unsupported user request: " + request.type()
                                );
                    };
                });
    }


    public ResponseEnvelope dispatchFollowQuery(RequestEnvelope request)
    {
        return execute(
                request,
                followQueryFacade,
                (facade, payload) ->
                {
                    UUID requestId = request.requestId();

                    return switch (request.type())
                    {
                        case FOLLOW_GET_FOLLOWERS ->
                                handleGetFollowers(requestId, payload, facade);

                        case FOLLOW_GET_FOLLOWINGS ->
                                handleGetFollowings(requestId, payload, facade);

                        default ->
                                throw new IllegalArgumentException(
                                        "Unsupported follow query request: " + request.type()
                                );
                    };
                }
        );
    }

    public ResponseEnvelope dispatchMedia(RequestEnvelope request)
    {
        return execute(
                request,
                mediaFacade,
                (facade, payload) ->
                {
                    UUID requestId = request.requestId();

                    return switch (request.type())
                    {
                        case MEDIA_DOWNLOAD ->
                                handleDownloadMedia(requestId, payload, facade);

                        case MEDIA_DELETE ->
                                handleDeleteMedia(requestId, payload, facade);

                        default ->
                                throw new IllegalArgumentException(
                                        "Unsupported media request: " + request.type()
                                );
                    };
                }
        );
    }


    public ResponseEnvelope dispatchReply(RequestEnvelope request)
    {
        return execute(
                request,
                tweetFacade,
                (facade, payload) ->
                {
                    UUID requestId = request.requestId();

                    GetRepliesRequest repliesRequest =
                            gson.fromJson(payload, GetRepliesRequest.class);

                    Result<List<TimelineTweet>> result =
                            facade.getReplies(repliesRequest);

                    if (result.isFailure())
                    {
                        return failureResponse(
                                requestId,
                                responseTypeFor(request.type()),
                                "GET_REPLIES_FAILED",
                                result.getError()
                        );
                    }

                    return successResponse(
                            requestId,
                            responseTypeFor(request.type()),
                            result.getData()
                    );
                });
    }

    public ResponseEnvelope dispatchNotification(RequestEnvelope request)
    {
        return execute(
                request,
                notificationFacade,
                (facade, payload) ->
                {
                    UUID requestId = request.requestId();

                    return switch (request.type())
                    {
                        case NOTIFICATION_GET ->
                                handleGetNotifications(requestId, payload, facade);

                        case NOTIFICATION_READ ->
                                handleReadNotification(requestId, payload, facade);

                        case NOTIFICATION_READ_ALL ->
                                handleReadAllNotifications(requestId, payload, facade);

                        default -> throw new IllegalArgumentException(
                                "Unsupported notification request: " + request.type());
                    };
                });
    }

    //===============================================================
    //                     DISPATCH AUTH
    //===============================================================
    private ResponseEnvelope handleRegister(
            UUID requestId,
            JsonElement payload,
            AuthFacade authFacade
    )
    {
        RegisterRequest request =
                gson.fromJson(payload, RegisterRequest.class);

        Result<AuthResponse> result = authFacade.register(request);

        if (result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.AUTH_REGISTER_RESPONSE,
                    "AUTH_REGISTER_FAILED",
                    result.getError()
            );
        }

        return successResponse(
                requestId,
                ResponseType.AUTH_REGISTER_RESPONSE,
                result.getData()
        );
    }

    private ResponseEnvelope handleLogin(
            UUID requestId,
            JsonElement payload,
            AuthFacade authFacade
    )
    {
        LoginRequest request =
                gson.fromJson(payload, LoginRequest.class);

        Result<AuthResponse> result = authFacade.login(request);

        if (result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.AUTH_LOGIN_RESPONSE,
                    "AUTH_LOGIN_FAILED",
                    result.getError()
            );
        }

        return successResponse(
                requestId,
                ResponseType.AUTH_LOGIN_RESPONSE,
                result.getData()
        );
    }

    private ResponseEnvelope handleLogout(
            UUID requestId,
            JsonElement payload,
            AuthFacade authFacade
    )
    {
        LogoutRequest request =
                gson.fromJson(payload, LogoutRequest.class);

        Result<LogoutResponse> result = authFacade.logout(request);

        if (result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.AUTH_LOGOUT_RESPONSE,
                    "AUTH_LOGOUT_FAILED",
                    result.getError()
            );
        }

        return successResponse(
                requestId,
                ResponseType.AUTH_LOGOUT_RESPONSE,
                result.getData()
        );
    }

    private ResponseEnvelope handleRefresh(
            UUID requestId,
            JsonElement payload,
            AuthFacade authFacade
    )
    {
        RefreshSessionRequest request =
                gson.fromJson(payload, RefreshSessionRequest.class);

        Result<AuthResponse> result = authFacade.refresh(request);

        if (result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.AUTH_REFRESH_RESPONSE,
                    "AUTH_REFRESH_FAILED",
                    result.getError()
            );
        }

        return successResponse(
                requestId,
                ResponseType.AUTH_REFRESH_RESPONSE,
                result.getData()
        );
    }

    private ResponseEnvelope handelRequestPasswordReset(
            UUID requestId,
            JsonElement payload,
            AuthFacade authFacade
    )
    {
        RequestPasswordResetRequest request =
                gson.fromJson(payload, RequestPasswordResetRequest.class);

        Result<RequestPasswordResetResponse> result = authFacade.requestPasswordReset(request);

        if (result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.AUTH_REQUEST_PASSWORD_RESET_RESPONSE,
                    "AUTH_REQUEST_PASSWORD_RESET_FAILED",
                    result.getError()
            );
        }

        return successResponse(
                requestId,
                ResponseType.AUTH_REQUEST_PASSWORD_RESET_RESPONSE,
                result.getData()
        );
    }

    private ResponseEnvelope handelVerifyPasswordResetCode(
            UUID requestId,
            JsonElement payload,
            AuthFacade authFacade
    )
    {
        VerifyPasswordResetCodeRequest request =
                gson.fromJson(payload, VerifyPasswordResetCodeRequest.class);

        Result<VerifyPasswordResetCodeResponse> result = authFacade.verifyPasswordResetCode(request);

        if (result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.AUTH_VERIFY_PASSWORD_RESET_CODE_RESPONSE,
                    "AUTH_VERIFY_PASSWORD_RESET_CODE_FAILED",
                    result.getError()
            );
        }

        return successResponse(
                requestId,
                ResponseType.AUTH_VERIFY_PASSWORD_RESET_CODE_RESPONSE,
                result.getData()
        );
    }

    private ResponseEnvelope handelResetPassword(
            UUID requestId,
            JsonElement payload,
            AuthFacade authFacade
    )
    {
        ResetPasswordRequest request =
                gson.fromJson(payload, ResetPasswordRequest.class);

        Result<ResetPasswordResponse> result = authFacade.resetPassword(request);

        if (result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.AUTH_RESET_PASSWORD_RESPONSE,
                    "AUTH_RESET_PASSWORD_FAILED",
                    result.getError()
            );
        }

        return successResponse(
                requestId,
                ResponseType.AUTH_RESET_PASSWORD_RESPONSE,
                result.getData()
        );
    }

    //===============================================================
    //                     DISPATCH CONVERSATION
    //===============================================================
    private ResponseEnvelope handleCreateConversation(
            UUID requestId,
            JsonElement payload,
            ConversationFacade facade
    )
    {
        CreateConversationRequest request = gson.fromJson(payload, CreateConversationRequest.class);

        Result<CreateConversationResponse> result = facade.createConversation(request);

        if (result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.CONVERSATION_CREATE_RESPONSE,
                    "CREATE_CONVERSATION_FAILED",
                    result.getError()
            );
        }

        return successResponse(
                requestId,
                ResponseType.CONVERSATION_CREATE_RESPONSE,
                result.getData()
        );
    }


    private ResponseEnvelope handleAddMember(
            UUID requestId,
            JsonElement payload,
            ConversationFacade facade)
    {
        AddConversationMemberRequest request = gson.fromJson(payload, AddConversationMemberRequest.class);

        Result<ConversationInfoResponse> result = facade.addMember(request);

        if (result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.CONVERSATION_ADD_MEMBER_RESPONSE,
                    "ADD_CONVERSATION_MEMBER_FAILED",
                    result.getError()
            );
        }

        return successResponse(
                requestId,
                ResponseType.CONVERSATION_ADD_MEMBER_RESPONSE,
                result.getData()
        );
    }


    private ResponseEnvelope handleDeleteMember(
            UUID requestId,
            JsonElement payload,
            ConversationFacade facade)
    {
        RemoveConversationMemberRequest request = gson.fromJson(payload, RemoveConversationMemberRequest.class);

        Result<ConversationInfoResponse> result = facade.deleteMember(request);

        if (result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.CONVERSATION_REMOVE_MEMBER_RESPONSE,
                    "REMOVE_CONVERSATION_MEMBER_FAILED",
                    result.getError()
            );
        }

        return successResponse(
                requestId,
                ResponseType.CONVERSATION_REMOVE_MEMBER_RESPONSE,
                result.getData()
        );
    }


    private ResponseEnvelope handleDeleteConversation(
            UUID requestId,
            JsonElement payload,
            ConversationFacade facade)
    {
        DeleteConversationRequest request = gson.fromJson(payload, DeleteConversationRequest.class);

        Result<DeleteConversationResponse> result = facade.deleteConversation(request);

        if (result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.CONVERSATION_DELETE_RESPONSE,
                    "DELETE_CONVERSATION_FAILED",
                    result.getError()
            );
        }

        return successResponse(
                requestId,
                ResponseType.CONVERSATION_DELETE_RESPONSE,
                result.getData()
        );
    }

    //===============================================================
    //                     DISPATCH MESSAGE
    //===============================================================
    private ResponseEnvelope handleSendMessage(
            UUID requestId,
            JsonElement payload,
            MessageFacade facade)
    {
        SendMessageRequest request = gson.fromJson(payload, SendMessageRequest.class);

        Result<ConversationStateResponse> result = facade.sendMessage(request);

        if (result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.MESSAGE_SEND_RESPONSE,
                    "MESSAGE_SEND_FAILED",
                    result.getError()
            );
        }

        return successResponse(
                requestId,
                ResponseType.MESSAGE_SEND_RESPONSE,
                result.getData()
        );
    }


    private ResponseEnvelope handleEditMessage(
            UUID requestId,
            JsonElement payload,
            MessageFacade facade)
    {
        EditMessageRequest request = gson.fromJson(payload, EditMessageRequest.class);

        Result<ConversationStateResponse> result = facade.editMessage(request);

        if (result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.MESSAGE_EDIT_RESPONSE,
                    "MESSAGE_EDIT_FAILED",
                    result.getError()
            );
        }

        return successResponse(
                requestId,
                ResponseType.MESSAGE_EDIT_RESPONSE,
                result.getData()
        );
    }


    private ResponseEnvelope handleDeleteMessage(
            UUID requestId,
            JsonElement payload,
            MessageFacade facade)
    {
        DeleteMessageRequest request = gson.fromJson(payload, DeleteMessageRequest.class);

        Result<ConversationStateResponse> result = facade.deleteMessage(request);

        if (result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.MESSAGE_DELETE_RESPONSE,
                    "MESSAGE_DELETE_FAILED",
                    result.getError()
            );
        }

        return successResponse(
                requestId,
                ResponseType.MESSAGE_DELETE_RESPONSE,
                result.getData()
        );
    }

    private ResponseEnvelope handleGetConversations(
            UUID requestId,
            JsonElement payload,
            ConversationFacade facade)
    {
        GetConversationsRequest request = gson.fromJson(payload, GetConversationsRequest.class);

        Result<GetConversationsResponse> result = facade.getConversations(request);

        if (result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.CONVERSATION_GET_RESPONSE,
                    "GET_CONVERSATIONS_FAILED",
                    result.getError()
            );
        }

        return successResponse(
                requestId,
                ResponseType.CONVERSATION_GET_RESPONSE,
                result.getData()
        );
    }


    private ResponseEnvelope handleGetMessage(
            UUID requestId,
            JsonElement payload,
            MessageFacade facade)
    {
        GetMessageRequest request = gson.fromJson(payload, GetMessageRequest.class);

        Result<MessageInfoResponse> result = facade.getMessage(request);

        if (result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.MESSAGE_GET_RESPONSE,
                    "MESSAGE_GET_FAILED",
                    result.getError()
            );
        }

        return successResponse(
                requestId,
                ResponseType.MESSAGE_GET_RESPONSE,
                result.getData()
        );
    }

    private ResponseEnvelope handleGetConversationMessages(
            UUID requestId,
            JsonElement payload,
            MessageFacade facade)
    {
        GetConversationMessagesRequest request = gson.fromJson(payload, GetConversationMessagesRequest.class);

        Result<ConversationMessagesResponse> result = facade.getConversationMessages(request);

        if (result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.MESSAGE_GET_CONVERSATION_RESPONSE,
                    "MESSAGE_GET_CONVERSATION_FAILED",
                    result.getError()
            );
        }

        return successResponse(
                requestId,
                ResponseType.MESSAGE_GET_CONVERSATION_RESPONSE,
                result.getData()
        );
    }


    //===============================================================
    //                     DISPATCH RELATION
    //===============================================================
    private ResponseEnvelope handleFollow(
            UUID requestId,
            JsonElement payload,
            RelationFacade facade)
    {
        FollowUserRequest request = gson.fromJson(payload, FollowUserRequest.class);

        Result<FollowResponse> result = facade.follow(request);

        if (result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.RELATION_FOLLOW_RESPONSE,
                    "FOLLOW_FAILED",
                    result.getError()
            );
        }

        return successResponse(
                requestId,
                ResponseType.RELATION_FOLLOW_RESPONSE,
                result.getData()
        );
    }


    private ResponseEnvelope handleUnfollow(
            UUID requestId,
            JsonElement payload,
            RelationFacade facade)
    {
        UnfollowUserRequest request = gson.fromJson(payload, UnfollowUserRequest.class);

        Result<FollowResponse> result = facade.unfollow(request);

        if (result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.RELATION_UNFOLLOW_RESPONSE,
                    "UNFOLLOW_FAILED",
                    result.getError()
            );
        }

        return successResponse(
                requestId,
                ResponseType.RELATION_UNFOLLOW_RESPONSE,
                result.getData()
        );
    }

    private ResponseEnvelope handleBlock(
            UUID requestId,
            JsonElement payload,
            RelationFacade facade)
    {
        BlockUserRequest request = gson.fromJson(payload, BlockUserRequest.class);

        Result<BlockActionResponse> result = facade.block(request);

        if (result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.RELATION_BLOCK_RESPONSE,
                    "BLOCK_FAILED",
                    result.getError()
            );
        }

        return successResponse(
                requestId,
                ResponseType.RELATION_BLOCK_RESPONSE,
                result.getData()
        );
    }


    private ResponseEnvelope handleUnblock(
            UUID requestId,
            JsonElement payload,
            RelationFacade facade)
    {
        UnblockUserRequest request = gson.fromJson(payload, UnblockUserRequest.class);

        Result<BlockActionResponse> result = facade.unblock(request);

        if (result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.RELATION_UNBLOCK_RESPONSE,
                    "UNBLOCK_FAILED",
                    result.getError()
            );
        }

        return successResponse(
                requestId,
                ResponseType.RELATION_UNBLOCK_RESPONSE,
                result.getData()
        );
    }


    private ResponseEnvelope handleMute(
            UUID requestId,
            JsonElement payload,
            RelationFacade facade)
    {
        MuteUserRequest request = gson.fromJson(payload, MuteUserRequest.class);

        Result<MuteResponse> result = facade.mute(request);

        if (result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.RELATION_MUTE_RESPONSE,
                    "MUTE_FAILED",
                    result.getError()
            );
        }

        return successResponse(
                requestId,
                ResponseType.RELATION_MUTE_RESPONSE,
                result.getData()
        );
    }

    private ResponseEnvelope handleUnmute(
            UUID requestId,
            JsonElement payload,
            RelationFacade facade)
    {
        UnmuteUserRequest request = gson.fromJson(payload, UnmuteUserRequest.class);

        Result<MuteResponse> result = facade.unmute(request);

        if (result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.RELATION_UNMUTE_RESPONSE,
                    "UNMUTE_FAILED",
                    result.getError()
            );
        }

        return successResponse(
                requestId,
                ResponseType.RELATION_UNMUTE_RESPONSE,
                result.getData()
        );
    }

    //===============================================================
    //                     DISPATCH TIMELINE
    //===============================================================
    private ResponseEnvelope handleGetTimeline(
            UUID requestId,
            JsonElement payload,
            TimelineFacade facade)
    {
        GetTimelineRequest request = gson.fromJson(payload, GetTimelineRequest.class);

        Result<GetTimelineResponse> result = facade.getTimeline(request);

        if (result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.TIMELINE_GET_RESPONSE,
                    "TIMELINE_GET_FAILED",
                    result.getError()
            );
        }

        return successResponse(
                requestId,
                ResponseType.TIMELINE_GET_RESPONSE,
                result.getData()
        );
    }


    //===============================================================
    //                     DISPATCH TWEET
    //===============================================================
    private ResponseEnvelope handleCreateTweet(
            UUID requestId,
            JsonElement payload,
            TweetFacade facade)
    {
        CreateTweetRequest request = gson.fromJson(payload, CreateTweetRequest.class);

        Result<TweetResponse> result =facade.createTweet(request);

        if (result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.TWEET_CREATE_RESPONSE,
                    "TWEET_CREATE_FAILED",
                    result.getError()
            );
        }

        return successResponse(
                requestId,
                ResponseType.TWEET_CREATE_RESPONSE,
                result.getData()
        );
    }



    private ResponseEnvelope handleDeleteTweet(
            UUID requestId,
            JsonElement payload,
            TweetFacade facade)
    {
        DeleteTweetRequest request = gson.fromJson(payload, DeleteTweetRequest.class);

        Result<TweetResponse> result = facade.deleteTweet(request);

        if (result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.TWEET_DELETE_RESPONSE,
                    "TWEET_DELETE_FAILED",
                    result.getError()
            );
        }

        return successResponse(
                requestId,
                ResponseType.TWEET_DELETE_RESPONSE,
                result.getData()
        );
    }


    private ResponseEnvelope handleEditTweet(
            UUID requestId,
            JsonElement payload,
            TweetFacade facade)
    {
        EditTweetRequest request = gson.fromJson(payload, EditTweetRequest.class);

        Result<TweetResponse> result = facade.editTweet(request);

        if (result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.TWEET_EDIT_RESPONSE,
                    "TWEET_EDIT_FAILED",
                    result.getError()
            );
        }

        return successResponse(
                requestId,
                ResponseType.TWEET_EDIT_RESPONSE,
                result.getData()
        );
    }


    private ResponseEnvelope handleLikeTweet(
            UUID requestId,
            JsonElement payload,
            TweetFacade facade)
    {
        LikeTweetRequest request = gson.fromJson(payload, LikeTweetRequest.class);

        Result<LikeResponse> result = facade.likeTweet(request);

        if (result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.TWEET_LIKE_RESPONSE,
                    "TWEET_LIKE_FAILED",
                    result.getError()
            );
        }

        return successResponse(
                requestId,
                ResponseType.TWEET_LIKE_RESPONSE,
                result.getData()
        );
    }


    private ResponseEnvelope handleUnlikeTweet(
            UUID requestId,
            JsonElement payload,
            TweetFacade facade)
    {
        UnlikeTweetRequest request = gson.fromJson(payload, UnlikeTweetRequest.class);

        Result<LikeResponse> result = facade.unlikeTweet(request);

        if (result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.TWEET_UNLIKE_RESPONSE,
                    "TWEET_UNLIKE_FAILED",
                    result.getError()
            );
        }

        return successResponse(
                requestId,
                ResponseType.TWEET_UNLIKE_RESPONSE,
                result.getData()
        );
    }


    private ResponseEnvelope handleReplyTweet(
            UUID requestId,
            JsonElement payload,
            TweetFacade facade)
    {
        ReplyTweetRequest request = gson.fromJson(payload, ReplyTweetRequest.class);

        Result<TweetResponse> result = facade.replyTweet(request);

        if (result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.TWEET_REPLY_RESPONSE,
                    "TWEET_REPLY_FAILED",
                    result.getError()
            );
        }

        return successResponse(
                requestId,
                ResponseType.TWEET_REPLY_RESPONSE,
                result.getData()
        );
    }


    private ResponseEnvelope handleGetTweet(
            UUID requestId,
            JsonElement payload,
            TweetFacade facade)
    {
        GetTweetRequest request = gson.fromJson(payload, GetTweetRequest.class);

        Result<TimelineTweet> result = facade.getTweet(request);

        if (result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.TWEET_GET_RESPONSE,
                    "TWEET_GET_FAILED",
                    result.getError()
            );
        }

        return successResponse(
                requestId,
                ResponseType.TWEET_GET_RESPONSE,
                result.getData()
        );
    }


    private ResponseEnvelope handleRetweet(
            UUID requestId,
            JsonElement payload,
            TweetFacade facade)
    {
        RetweetRequest request = gson.fromJson(payload, RetweetRequest.class);

        Result<TweetResponse> result = facade.retweet(request);

        if (result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.TWEET_RETWEET_RESPONSE,
                    "TWEET_RETWEET_FAILED",
                    result.getError()
            );
        }

        return successResponse(
                requestId,
                ResponseType.TWEET_RETWEET_RESPONSE,
                result.getData()
        );
    }


    private ResponseEnvelope handleUnretweet(
            UUID requestId,
            JsonElement payload,
            TweetFacade facade)
    {
        UnretweetRequest request = gson.fromJson(payload, UnretweetRequest.class);

        Result<TweetResponse> result = facade.unretweet(request);

        if (result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.TWEET_UNRETWEET_RESPONSE,
                    "TWEET_UNRETWEET_FAILED",
                    result.getError()
            );
        }

        return successResponse(
                requestId,
                ResponseType.TWEET_UNRETWEET_RESPONSE,
                result.getData()
        );
    }

    private ResponseEnvelope handleGetNotifications(
            UUID requestId,
            JsonElement payload,
            NotificationFacade facade)
    {
        GetNotificationsRequest request =
                gson.fromJson(payload, GetNotificationsRequest.class);

        Result<List<NotificationResponse>> result =
                facade.getNotifications(request);

        if (result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.NOTIFICATION_GET_RESPONSE,
                    "NOTIFICATION_GET_FAILED",
                    result.getError()
            );
        }

        return successResponse(
                requestId,
                ResponseType.NOTIFICATION_GET_RESPONSE,
                result.getData()
        );
    }

    private ResponseEnvelope handleReadNotification(
            UUID requestId,
            JsonElement payload,
            NotificationFacade facade)
    {
        ReadNotificationRequest request =
                gson.fromJson(payload, ReadNotificationRequest.class);

        Result<NotificationResponse> result =
                facade.readNotification(request);

        if (result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.NOTIFICATION_READ_RESPONSE,
                    "NOTIFICATION_READ_FAILED",
                    result.getError()
            );
        }

        return successResponse(
                requestId,
                ResponseType.NOTIFICATION_READ_RESPONSE,
                result.getData()
        );
    }

    private ResponseEnvelope handleReadAllNotifications(
            UUID requestId,
            JsonElement payload,
            NotificationFacade facade)
    {
        ReadAllNotificationsRequest request =
                gson.fromJson(payload, ReadAllNotificationsRequest.class);

        Result<Integer> result =
                facade.readAllNotifications(request);

        if (result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.NOTIFICATION_READ_ALL_RESPONSE,
                    "NOTIFICATION_READ_ALL_FAILED",
                    result.getError()
            );
        }

        return successResponse(
                requestId,
                ResponseType.NOTIFICATION_READ_ALL_RESPONSE,
                result.getData()
        );
    }

    //===============================================================
    //                     DISPATCH USER
    //===============================================================
    private ResponseEnvelope handleGetProfile(
            UUID requestId,
            JsonElement payload,
            UserFacade facade)
    {
        GetProfileRequest request =
                gson.fromJson(payload, GetProfileRequest.class);


        Result<ProfileInfoResponse> result =
                facade.getProfile(request);


        if(result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.USER_GET_PROFILE_RESPONSE,
                    "GET_PROFILE_FAILED",
                    result.getError()
            );
        }


        return successResponse(
                requestId,
                ResponseType.USER_GET_PROFILE_RESPONSE,
                result.getData()
        );
    }

    private ResponseEnvelope handleSearchUsers(
            UUID requestId,
            JsonElement payload,
            UserFacade facade)
    {
        SearchUsersRequest request =
                gson.fromJson(payload, SearchUsersRequest.class);


        Result<List<UserSearchResponse>> result =
                facade.searchUsers(request);


        if(result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.USER_SEARCH_RESPONSE,
                    "SEARCH_USERS_FAILED",
                    result.getError()
            );
        }


        return successResponse(
                requestId,
                ResponseType.USER_SEARCH_RESPONSE,
                result.getData()
        );
    }

    private ResponseEnvelope handleUpdateProfile(
            UUID requestId,
            JsonElement payload,
            UserFacade facade)
    {
        UpdateProfileRequest request = gson.fromJson(payload, UpdateProfileRequest.class);


        Result<Void> result = facade.updateProfile(request);


        if(result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.USER_UPDATE_PROFILE_RESPONSE,
                    "UPDATE_PROFILE_FAILED",
                    result.getError()
            );
        }


        return successResponse(
                requestId,
                ResponseType.USER_UPDATE_PROFILE_RESPONSE,
                null
        );
    }

    private ResponseEnvelope handleUpdateBio(
            UUID requestId,
            JsonElement payload,
            UserFacade facade)
    {
        UpdateBioRequest request =
                gson.fromJson(payload, UpdateBioRequest.class);


        Result<UpdateBioResponse> result =
                facade.updateBio(request);


        if(result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.USER_UPDATE_BIO_RESPONSE,
                    "UPDATE_BIO_FAILED",
                    result.getError()
            );
        }


        return successResponse(
                requestId,
                ResponseType.USER_UPDATE_BIO_RESPONSE,
                result.getData()
        );
    }

    private ResponseEnvelope handleUpdateAvatar(
            UUID requestId,
            JsonElement payload,
            UserFacade facade)
    {
        UpdateAvatarRequest request =
                gson.fromJson(payload, UpdateAvatarRequest.class);


        Result<UpdateAvatarResponse> result =
                facade.updateAvatar(request);


        if(result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.USER_UPDATE_AVATAR_RESPONSE,
                    "UPDATE_AVATAR_FAILED",
                    result.getError()
            );
        }


        return successResponse(
                requestId,
                ResponseType.USER_UPDATE_AVATAR_RESPONSE,
                result.getData()
        );
    }


    private ResponseEnvelope handleUpdateBanner(
            UUID requestId,
            JsonElement payload,
            UserFacade facade)
    {
        UpdateBannerRequest request =
                gson.fromJson(payload, UpdateBannerRequest.class);


        Result<UpdateBannerResponse> result =
                facade.updateBanner(request);


        if(result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.USER_UPDATE_BANNER_RESPONSE,
                    "UPDATE_BANNER_FAILED",
                    result.getError()
            );
        }


        return successResponse(
                requestId,
                ResponseType.USER_UPDATE_BANNER_RESPONSE,
                result.getData()
        );
    }

    private ResponseEnvelope handleUpdateEmail(
            UUID requestId,
            JsonElement payload,
            UserFacade facade)
    {
        UpdateEmailRequest request =
                gson.fromJson(payload, UpdateEmailRequest.class);


        Result<Void> result =
                facade.updateEmail(request);


        if(result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.USER_UPDATE_EMAIL_RESPONSE,
                    "UPDATE_EMAIL_FAILED",
                    result.getError()
            );
        }


        return successResponse(
                requestId,
                ResponseType.USER_UPDATE_EMAIL_RESPONSE,
                null
        );
    }


    private ResponseEnvelope handleUpdatePassword(
            UUID requestId,
            JsonElement payload,
            UserFacade facade)
    {
        UpdatePasswordRequest request =
                gson.fromJson(payload, UpdatePasswordRequest.class);


        Result<Void> result =
                facade.updatePassword(request);


        if(result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.USER_UPDATE_PASSWORD_RESPONSE,
                    "UPDATE_PASSWORD_FAILED",
                    result.getError()
            );
        }


        return successResponse(
                requestId,
                ResponseType.USER_UPDATE_PASSWORD_RESPONSE,
                null
        );
    }


    private ResponseEnvelope handleDeleteAccount(
            UUID requestId,
            JsonElement payload,
            UserFacade facade)
    {
        DeleteAccountRequest request =
                gson.fromJson(payload, DeleteAccountRequest.class);


        Result<Void> result =
                facade.deleteAccount(request);


        if(result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.USER_DELETE_ACCOUNT_RESPONSE,
                    "DELETE_ACCOUNT_FAILED",
                    result.getError()
            );
        }


        return successResponse(
                requestId,
                ResponseType.USER_DELETE_ACCOUNT_RESPONSE,
                null
        );
    }

    private ResponseEnvelope handleUpdateCompleteProfile(
            UUID requestId,
            JsonElement payload,
            UserFacade facade)
    {
        UpdateCompleteProfileRequest request =
                gson.fromJson(
                        payload,
                        UpdateCompleteProfileRequest.class
                );

        Result<UpdateCompleteProfileResponse> result =
                facade.updateCompleteProfile(request);


        if (result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.USER_UPDATE_COMPLETE_PROFILE_RESPONSE,
                    "UPDATE_COMPLETE_PROFILE_FAILED",
                    result.getError()
            );
        }

        return successResponse(
                requestId,
                ResponseType.USER_UPDATE_COMPLETE_PROFILE_RESPONSE,
                gson.toJsonTree(result.getData())
        );
    }

    private ResponseEnvelope handleIsFollow(
            UUID requestId,
            JsonElement payload,
            UserFacade facade)
    {
        GetIsFollowingRequest request =
                gson.fromJson(payload, GetIsFollowingRequest.class);

        Result<GetIsFollowingResponse> result = facade.isFollow(request);

        if (result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.USER_GET_IS_FOLLOW_RESPONSE,
                    "GET_IS_FOLLOW_FAILED",
                    result.getError()
            );
        }

        return successResponse(
                requestId,
                ResponseType.USER_GET_IS_FOLLOW_RESPONSE,
                result.getData()
        );
    }

    private ResponseEnvelope handleIsLike(
            UUID requestId,
            JsonElement payload,
            UserFacade facade)
    {
        GetIsLikedRequest request =
                gson.fromJson(payload, GetIsLikedRequest.class);

        Result<GetIsLikedResponse> result =
                facade.isLiked(request);

        if (result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.USER_GET_IS_LIKE_RESPONSE,
                    "GET_IS_LIKE_FAILED",
                    result.getError()
            );
        }

        return successResponse(
                requestId,
                ResponseType.USER_GET_IS_LIKE_RESPONSE,
                result.getData()
        );
    }

    private ResponseEnvelope successResponse(UUID requestId, ResponseType type, Object body)
    {
        return ResponseEnvelope.success(
                requestId,
                type.toWire(),
                gson.toJsonTree(body)
        );
    }

    private ResponseEnvelope failureResponse(
            UUID requestId,
            ResponseType type,
            String errorCode,
            String errorMessage
    )
    {
        return ResponseEnvelope.failure(
                requestId,
                type.toWire(),
                errorCode,
                errorMessage
        );
    }

    //===============================================================
    //                     DISPATCH FOLLOW QUERY
    //===============================================================
    private ResponseEnvelope handleGetFollowers(
            UUID requestId,
            JsonElement payload,
            FollowQueryFacade facade)
    {
        GetFollowersRequest request =
                gson.fromJson(
                        payload,
                        GetFollowersRequest.class
                );

        Result<GetFollowersResponse> result =
                facade.getFollowers(request);

        if (result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.FOLLOW_GET_FOLLOWERS_RESPONSE,
                    "GET_FOLLOWERS_FAILED",
                    result.getError()
            );
        }

        return successResponse(
                requestId,
                ResponseType.FOLLOW_GET_FOLLOWERS_RESPONSE,
                gson.toJsonTree(result.getData())
        );
    }


    private ResponseEnvelope handleGetFollowings(
            UUID requestId,
            JsonElement payload,
            FollowQueryFacade facade)
    {
        GetFollowingsRequest request =
                gson.fromJson(
                        payload,
                        GetFollowingsRequest.class
                );

        Result<FollowingsResponse> result =
                facade.getFollowings(request);

        if (result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.FOLLOW_GET_FOLLOWINGS_RESPONSE,
                    "GET_FOLLOWINGS_FAILED",
                    result.getError()
            );
        }

        return successResponse(
                requestId,
                ResponseType.FOLLOW_GET_FOLLOWINGS_RESPONSE,
                gson.toJsonTree(result.getData())
        );
    }


    //===============================================================
    //                     DISPATCH MEDIA
    //===============================================================
    private ResponseEnvelope handleDownloadMedia(
            UUID requestId,
            JsonElement payload,
            MediaFacade facade)
    {
        DownloadMediaRequest request =
                gson.fromJson(
                        payload,
                        DownloadMediaRequest.class
                );

        Result<DownloadMediaResponse> result =
                facade.downloadMedia(request);

        if (result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.MEDIA_DOWNLOAD_RESPONSE,
                    "DOWNLOAD_MEDIA_FAILED",
                    result.getError()
            );
        }

        return successResponse(
                requestId,
                ResponseType.MEDIA_DOWNLOAD_RESPONSE,
                gson.toJsonTree(result.getData())
        );
    }

    private ResponseEnvelope handleDeleteMedia(
            UUID requestId,
            JsonElement payload,
            MediaFacade facade)
    {
        DeleteMediaRequest request =
                gson.fromJson(
                        payload,
                        DeleteMediaRequest.class
                );

        Result<Void> result =
                facade.deleteMedia(request);

        if (result.isFailure())
        {
            return failureResponse(
                    requestId,
                    ResponseType.MEDIA_DELETE_RESPONSE,
                    "DELETE_MEDIA_FAILED",
                    result.getError()
            );
        }

        return successResponse(
                requestId,
                ResponseType.MEDIA_DELETE_RESPONSE,
                null
        );
    }

    private ResponseType responseTypeFor(RequestType requestType)
    {
        return switch (requestType)
        {
            case AUTH_REGISTER -> ResponseType.AUTH_REGISTER_RESPONSE;
            case AUTH_LOGIN -> ResponseType.AUTH_LOGIN_RESPONSE;
            case AUTH_LOGOUT -> ResponseType.AUTH_LOGOUT_RESPONSE;
            case AUTH_REFRESH -> ResponseType.AUTH_REFRESH_RESPONSE;
            case AUTH_REQUEST_PASSWORD_RESET -> ResponseType.AUTH_REQUEST_PASSWORD_RESET_RESPONSE;
            case AUTH_VERIFY_PASSWORD_RESET_CODE -> ResponseType.AUTH_VERIFY_PASSWORD_RESET_CODE_RESPONSE;
            case AUTH_RESET_PASSWORD -> ResponseType.AUTH_RESET_PASSWORD_RESPONSE;
            case CONVERSATION_CREATE ->ResponseType.CONVERSATION_CREATE_RESPONSE;
            case CONVERSATION_GET -> ResponseType.CONVERSATION_GET_RESPONSE;
            case MEMBER_ADD -> ResponseType.CONVERSATION_ADD_MEMBER_RESPONSE;
            case MEMBER_DELETE -> ResponseType.CONVERSATION_REMOVE_MEMBER_RESPONSE;
            case CONVERSATION_DELETE -> ResponseType.CONVERSATION_DELETE_RESPONSE;
            case MESSAGE_SEND -> ResponseType.MESSAGE_SEND_RESPONSE;
            case MESSAGE_EDIT -> ResponseType.MESSAGE_EDIT_RESPONSE;
            case MESSAGE_DELETE -> ResponseType.MESSAGE_DELETE_RESPONSE;
            case MESSAGE_GET -> ResponseType.MESSAGE_GET_RESPONSE;
            case MESSAGE_GET_CONVERSATION -> ResponseType.MESSAGE_GET_CONVERSATION_RESPONSE;
            case RELATION_FOLLOW -> ResponseType.RELATION_FOLLOW_RESPONSE;
            case RELATION_UNFOLLOW -> ResponseType.RELATION_UNFOLLOW_RESPONSE;
            case RELATION_BLOCK -> ResponseType.RELATION_BLOCK_RESPONSE;
            case RELATION_UNBLOCK -> ResponseType.RELATION_UNBLOCK_RESPONSE;
            case RELATION_MUTE -> ResponseType.RELATION_MUTE_RESPONSE;
            case RELATION_UNMUTE -> ResponseType.RELATION_UNMUTE_RESPONSE;
            case TIMELINE_GET -> ResponseType.TIMELINE_GET_RESPONSE;
            case TWEET_CREATE -> ResponseType.TWEET_CREATE_RESPONSE;
            case TWEET_DELETE -> ResponseType.TWEET_DELETE_RESPONSE;
            case TWEET_EDIT -> ResponseType.TWEET_EDIT_RESPONSE;
            case TWEET_LIKE -> ResponseType.TWEET_LIKE_RESPONSE;
            case TWEET_UNLIKE -> ResponseType.TWEET_UNLIKE_RESPONSE;
            case TWEET_REPLY -> ResponseType.TWEET_REPLY_RESPONSE;
            case TWEET_RETWEET -> ResponseType.TWEET_RETWEET_RESPONSE;
            case TWEET_UNRETWEET -> ResponseType.TWEET_UNRETWEET_RESPONSE;
            case TWEET_GET -> ResponseType.TWEET_GET_RESPONSE;
            case TWEET_GET_REPLIES -> ResponseType.TWEET_GET_REPLY_RESPONSE;
            case NOTIFICATION_GET -> ResponseType.NOTIFICATION_GET_RESPONSE;
            case NOTIFICATION_READ -> ResponseType.NOTIFICATION_READ_RESPONSE;
            case NOTIFICATION_READ_ALL -> ResponseType.NOTIFICATION_READ_ALL_RESPONSE;
            case USER_GET_PROFILE -> ResponseType.USER_GET_PROFILE_RESPONSE;
            case USER_SEARCH -> ResponseType.USER_SEARCH_RESPONSE;
            case USER_UPDATE_PROFILE -> ResponseType.USER_UPDATE_PROFILE_RESPONSE;
            case USER_UPDATE_BIO -> ResponseType.USER_UPDATE_BIO_RESPONSE;
            case USER_UPDATE_AVATAR -> ResponseType.USER_UPDATE_AVATAR_RESPONSE;
            case USER_UPDATE_BANNER -> ResponseType.USER_UPDATE_BANNER_RESPONSE;
            case USER_UPDATE_EMAIL -> ResponseType.USER_UPDATE_EMAIL_RESPONSE;
            case USER_UPDATE_PASSWORD -> ResponseType.USER_UPDATE_PASSWORD_RESPONSE;
            case USER_DELETE_ACCOUNT -> ResponseType.USER_DELETE_ACCOUNT_RESPONSE;
            case USER_UPDATE_COMPLETE_PROFILE -> ResponseType.USER_UPDATE_COMPLETE_PROFILE_RESPONSE;
            case FOLLOW_GET_FOLLOWINGS -> ResponseType.FOLLOW_GET_FOLLOWINGS_RESPONSE;
            case FOLLOW_GET_FOLLOWERS -> ResponseType.FOLLOW_GET_FOLLOWERS_RESPONSE;
            case MEDIA_DELETE -> ResponseType.MEDIA_DELETE_RESPONSE;
            case MEDIA_DOWNLOAD -> ResponseType.MEDIA_DOWNLOAD_RESPONSE;
            case USER_GET_IS_FOLLOW -> ResponseType.USER_GET_IS_FOLLOW_RESPONSE;
            case USER_GET_IS_LIKE -> ResponseType.USER_GET_IS_LIKE_RESPONSE;
        };
    }

    private void rollbackQuietly(EntityTransaction tx) {
        if (tx != null && tx.isActive())
        {
            tx.rollback();
        }
    }

    private void closeQuietly(EntityManager em)
    {
        if (em != null && em.isOpen())
        {
            em.close();
        }
    }
}
