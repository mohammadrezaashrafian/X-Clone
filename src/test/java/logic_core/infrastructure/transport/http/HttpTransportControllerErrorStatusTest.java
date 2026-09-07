package logic_core.infrastructure.transport.http;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import logic_core.app.security.SessionPrincipalResolver;
import logic_core.infrastructure.transport.RequestEnvelope;
import logic_core.infrastructure.transport.RequestType;
import logic_core.infrastructure.transport.ResponseEnvelope;
import logic_core.infrastructure.transport.server.RequestDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Verifies that the real {@link HttpTransportController} applies the centralized
 * {@link ErrorStatusMapper} to dispatched failure envelopes. The dispatcher is
 * stubbed because no production use case currently propagates typed exceptions
 * to it (they are converted to {@code Result.failure} inside the use cases);
 * this test proves the HTTP contract for those standardized codes end to end
 * through the controller without introducing artificial production behavior.
 */
class HttpTransportControllerErrorStatusTest
{
    private final Gson gson = new GsonBuilder().serializeNulls().create();

    private RequestDispatcher dispatcher;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp()
    {
        dispatcher = mock(RequestDispatcher.class);

        HttpTransportController controller = new HttpTransportController(
                dispatcher,
                gson,
                mock(SessionPrincipalResolver.class)
        );

        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new ApiTransportExceptionHandler(gson))
                .build();
    }

    @Test
    void forbiddenErrorCode_mapsTo403() throws Exception
    {
        assertStatusForCode("FORBIDDEN", 403);
    }

    @Test
    void notFoundErrorCode_mapsTo404() throws Exception
    {
        assertStatusForCode("NOT_FOUND", 404);
    }

    @Test
    void conflictErrorCode_mapsTo409() throws Exception
    {
        assertStatusForCode("CONFLICT", 409);
    }

    @Test
    void validationErrorCode_mapsTo400() throws Exception
    {
        assertStatusForCode("VALIDATION_ERROR", 400);
    }

    @Test
    void databaseErrorCode_mapsTo500() throws Exception
    {
        assertStatusForCode("DATABASE_ERROR", 500);
    }

    @Test
    void unexpectedErrorCode_mapsTo500() throws Exception
    {
        assertStatusForCode("UNEXPECTED_ERROR", 500);
    }

    @Test
    void unknownRequestCode_mapsTo400() throws Exception
    {
        assertStatusForCode("UNKNOWN_REQUEST", 400);
    }

    @Test
    void routeSpecificBusinessFailureCode_remains200() throws Exception
    {
        assertStatusForCode("TWEET_GET_FAILED", 200);
    }

    @Test
    void successEnvelope_remains200() throws Exception
    {
        when(dispatcher.dispatch(any(RequestEnvelope.class)))
                .thenReturn(ResponseEnvelope.success(
                        UUID.randomUUID(),
                        "AUTH_LOGIN_RESPONSE",
                        gson.toJsonTree("{}")
                ));

        MockHttpServletResponse response = sendAnonymous();

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void protectedRequestWithoutCredentials_returns401AuthRequired() throws Exception
    {
        RequestEnvelope request = new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.TWEET_GET,
                gson.toJsonTree("{}"),
                null
        );

        MockHttpServletResponse response = mockMvc.perform(post("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gson.toJson(request)))
                .andReturn()
                .getResponse();

        assertThat(response.getStatus()).isEqualTo(401);

        ResponseEnvelope envelope =
                gson.fromJson(response.getContentAsString(), ResponseEnvelope.class);
        assertThat(envelope.errorCode()).isEqualTo("AUTH_REQUIRED");
    }

    private void assertStatusForCode(String errorCode, int expectedStatus) throws Exception
    {
        when(dispatcher.dispatch(any(RequestEnvelope.class)))
                .thenReturn(ResponseEnvelope.failure(
                        UUID.randomUUID(),
                        "AUTH_LOGIN_RESPONSE",
                        errorCode,
                        "stubbed failure"
                ));

        MockHttpServletResponse response = sendAnonymous();

        assertThat(response.getStatus()).isEqualTo(expectedStatus);

        ResponseEnvelope envelope =
                gson.fromJson(response.getContentAsString(), ResponseEnvelope.class);
        assertThat(envelope.errorCode()).isEqualTo(errorCode);
    }

    private MockHttpServletResponse sendAnonymous() throws Exception
    {
        // AUTH_LOGIN is anonymous, so the request reaches the (stubbed)
        // dispatcher without needing session resolution.
        RequestEnvelope request = new RequestEnvelope(
                UUID.randomUUID(),
                RequestType.AUTH_LOGIN,
                gson.toJsonTree("{}"),
                null
        );

        return mockMvc.perform(post("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(gson.toJson(request)))
                .andReturn()
                .getResponse();
    }
}