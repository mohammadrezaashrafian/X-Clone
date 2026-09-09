package logic_core.infrastructure.transport.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import logic_core.app.dto.media.UploadFile;
import logic_core.app.dto.request.UploadMediaRequest;
import logic_core.app.dto.response.UploadMediaResponse;
import logic_core.infrastructure.transport.RequestEnvelope;
import logic_core.infrastructure.transport.RequestType;
import logic_core.infrastructure.transport.ResponseEnvelope;
import logic_core.infrastructure.transport.server.RequestDispatcher;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/***
 * Proves that {@link RequestDispatcher} dispatches {@link RequestType#MEDIA_UPLOAD}
 * to the media facade and returns a typed {@link UploadMediaResponse} without
 * collapsing it into a generic error path.
 */
class MediaUploadDispatchTest
{
    private final Gson gson = new GsonBuilder().serializeNulls().create();

    private final logic_core.app.facade.MediaFacade mediaFacade = mock(logic_core.app.facade.MediaFacade.class);

    private final RequestDispatcher dispatcher = new RequestDispatcher(
            gson,
            mock(logic_core.app.facade.AuthFacade.class),
            mock(logic_core.app.facade.BookmarkFacade.class),
            mock(logic_core.app.facade.ConversationFacade.class),
            mock(logic_core.app.facade.FollowQueryFacade.class),
            mock(logic_core.app.facade.HashtagFacade.class),
            mediaFacade,
            mock(logic_core.app.facade.MessageFacade.class),
            mock(logic_core.app.facade.NotificationFacade.class),
            mock(logic_core.app.facade.PollFacade.class),
            mock(logic_core.app.facade.RelationFacade.class),
            mock(logic_core.app.facade.TimelineFacade.class),
            mock(logic_core.app.facade.TweetFacade.class),
            mock(logic_core.app.facade.UserFacade.class)
    );

    @Test
    void uploadMedia_dispatchesToFacadeAndReturnsUploadMediaResponse()
    {
        UploadMediaRequest uploadRequest = new UploadMediaRequest(
                "session-token",
                new UploadFile("cat.gif", "image/gif", new byte[]{1, 2, 3})
        );

        UploadMediaResponse uploadResponse = new UploadMediaResponse(
                UUID.randomUUID(),
                "/media/tweets/cat.gif",
                "cat.gif",
                3L,
                logic_core.domain.model.media.MediaType.GIF
        );

        when(mediaFacade.uploadMedia(any(UploadMediaRequest.class)))
                .thenReturn(logic_core.common.result.Result.success(uploadResponse));

        RequestEnvelope request = new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.MEDIA_UPLOAD,
                gson.toJsonTree(uploadRequest),
                null
        );

        ResponseEnvelope response = dispatcher.dispatch(request);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.errorCode()).isNull();

        UploadMediaResponse actual = gson.fromJson(response.getData(), UploadMediaResponse.class);
        assertThat(actual.mediaId()).isEqualTo(uploadResponse.mediaId());
        assertThat(actual.mediaUrl()).isEqualTo("/media/tweets/cat.gif");
        assertThat(actual.originalFilename()).isEqualTo("cat.gif");
        assertThat(actual.fileSizeBytes()).isEqualTo(3L);
        assertThat(actual.mediaType()).isEqualTo(logic_core.domain.model.media.MediaType.GIF);
    }
}
