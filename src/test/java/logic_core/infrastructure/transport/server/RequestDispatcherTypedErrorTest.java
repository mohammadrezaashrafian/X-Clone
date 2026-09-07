package logic_core.infrastructure.transport.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import logic_core.app.dto.request.LikeTweetRequest;
import logic_core.app.facade.AuthFacade;
import logic_core.app.facade.ConversationFacade;
import logic_core.app.facade.FollowQueryFacade;
import logic_core.app.facade.MediaFacade;
import logic_core.app.facade.MessageFacade;
import logic_core.app.facade.NotificationFacade;
import logic_core.app.facade.RelationFacade;
import logic_core.app.facade.TimelineFacade;
import logic_core.app.facade.TweetFacade;
import logic_core.app.facade.UserFacade;
import logic_core.common.exception.ForbiddenException;
import logic_core.common.exception.NotFoundException;
import logic_core.infrastructure.transport.RequestEnvelope;
import logic_core.infrastructure.transport.RequestType;
import logic_core.infrastructure.transport.ResponseEnvelope;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves that {@link RequestDispatcher#execute} surfaces the typed
 * {@code AppException} error code (e.g. {@code FORBIDDEN}, {@code NOT_FOUND})
 * instead of collapsing every application exception into {@code APP_ERROR}.
 */
class RequestDispatcherTypedErrorTest
{
    private final Gson gson = new GsonBuilder().serializeNulls().create();

    private final AuthFacade authFacade = mock(AuthFacade.class);
    private final ConversationFacade conversationFacade = mock(ConversationFacade.class);
    private final FollowQueryFacade followQueryFacade = mock(FollowQueryFacade.class);
    private final MediaFacade mediaFacade = mock(MediaFacade.class);
    private final MessageFacade messageFacade = mock(MessageFacade.class);
    private final NotificationFacade notificationFacade = mock(NotificationFacade.class);
    private final RelationFacade relationFacade = mock(RelationFacade.class);
    private final TimelineFacade timelineFacade = mock(TimelineFacade.class);
    private final TweetFacade tweetFacade = mock(TweetFacade.class);
    private final UserFacade userFacade = mock(UserFacade.class);

    private final RequestDispatcher dispatcher = new RequestDispatcher(
            gson,
            authFacade,
            conversationFacade,
            followQueryFacade,
            mediaFacade,
            messageFacade,
            notificationFacade,
            relationFacade,
            timelineFacade,
            tweetFacade,
            userFacade
    );

    @Test
    void forbiddenException_errorCodePropagates()
    {
        when(tweetFacade.likeTweet(any(LikeTweetRequest.class)))
                .thenThrow(new ForbiddenException("You cannot like this tweet."));

        ResponseEnvelope response = dispatchLike();

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.errorCode()).isEqualTo("FORBIDDEN");
        assertThat(response.type()).isEqualTo("TWEET_LIKE_RESPONSE");
        assertThat(response.errorMessage()).isEqualTo("You cannot like this tweet.");
    }

    @Test
    void notFoundException_errorCodePropagates()
    {
        when(tweetFacade.likeTweet(any(LikeTweetRequest.class)))
                .thenThrow(new NotFoundException("Tweet not found."));

        ResponseEnvelope response = dispatchLike();

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.errorCode()).isEqualTo("NOT_FOUND");
        assertThat(response.type()).isEqualTo("TWEET_LIKE_RESPONSE");
    }

    private ResponseEnvelope dispatchLike()
    {
        LikeTweetRequest likeRequest =
                new LikeTweetRequest(UUID.randomUUID(), "token");

        RequestEnvelope request = new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_LIKE,
                gson.toJsonTree(likeRequest),
                null
        );

        return dispatcher.dispatch(request);
    }
}