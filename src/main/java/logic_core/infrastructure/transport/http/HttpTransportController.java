package logic_core.infrastructure.transport.http;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import jakarta.servlet.http.HttpServletResponse;
import logic_core.app.security.AuthContext;
import logic_core.app.security.SessionPrincipalResolver;
import logic_core.common.security.AuthPrincipal;
import logic_core.infrastructure.transport.RequestEnvelope;
import logic_core.infrastructure.transport.RequestType;
import logic_core.infrastructure.transport.ResponseEnvelope;
import logic_core.infrastructure.transport.ResponseType;
import logic_core.infrastructure.transport.server.RequestDispatcher;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.Set;

/**
 * HTTP transport for the existing socket protocol.
 *
 * <p>{@code POST /api} accepts the exact same JSON {@link RequestEnvelope} that the socket
 * transport sends and returns the {@link ResponseEnvelope} produced by the existing
 * {@link RequestDispatcher}, serialized with the same {@link Gson} bean the socket
 * transport uses. Malformed input produces the identical failure envelope the socket
 * {@code ClientHandler} produces, so no second error-handling system is introduced.
 *
 * <h2>HTTP-layer authentication</h2>
 *
 * <p>Every protected request is authenticated before it reaches the dispatcher:
 *
 * <ul>
 *   <li><b>Anonymous operations</b> (registration, login, session refresh and the
 *       password-reset flow) never require credentials.</li>
 *   <li><b>Protected operations</b> must present a valid session token. The token is read
 *       from the request payload ({@code sessionToken}, with {@code token} as a fallback
 *       for the few DTOs that use that name) or, when the payload carries no token field,
 *       from the envelope-level {@code token} field - the same places the existing
 *       envelope architecture transports the session token today. Requests with missing or
 *       invalid/expired/revoked credentials are rejected with HTTP {@code 401 Unauthorized}
 *       and a failure envelope.</li>
 *   <li>Valid credentials are resolved into an {@link AuthPrincipal} that is exposed to the
 *       application layer through {@link AuthContext} for the duration of the request and
 *       cleared afterwards. The principal is derived from the authenticated session - never
 *       from caller-controlled identifiers such as {@code actorId} or {@code targetUserId}
 *       inside the payload.</li>
 * </ul>
 *
 * <p>Application-layer authentication and authorization are preserved unchanged: every
 * authenticated use case still validates the payload token through
 * {@code AuthLockOrchestrator.lockAndGetContextByToken(...)} inside its own transaction
 * (including the user row-locking/concurrency semantics). The transport check is an
 * additional gate, not a replacement.
 *
 * <p>The final HTTP status/error contract (e.g. whether authorization failures also map to
 * HTTP status codes, and where the session token ultimately travels) belongs to the API
 * contract &amp; error-handling milestone ([V2.0 #5]); this controller keeps the envelope
 * wire format unchanged and only adds {@code 401} for unauthenticated access.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class HttpTransportController
{
    /**
     * Request types that are legitimately reachable without an authenticated session.
     */
    private static final Set<RequestType> ANONYMOUS_REQUEST_TYPES = Set.of(
            RequestType.AUTH_REGISTER,
            RequestType.AUTH_LOGIN,
            RequestType.AUTH_REFRESH,
            RequestType.AUTH_REQUEST_PASSWORD_RESET,
            RequestType.AUTH_VERIFY_PASSWORD_RESET_CODE,
            RequestType.AUTH_RESET_PASSWORD
    );

    private static final String AUTH_FAILURE_TYPE = "UNAUTHORIZED";
    private static final String AUTH_REQUIRED_CODE = "AUTH_REQUIRED";
    private static final String UNAUTHORIZED_CODE = "UNAUTHORIZED";

    private final RequestDispatcher dispatcher;
    private final Gson gson;
    private final SessionPrincipalResolver sessionPrincipalResolver;

    @PostMapping(
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public String dispatch(
            @RequestBody String rawBody,
            HttpServletResponse servletResponse
    )
    {
        try
        {
            RequestEnvelope request = parseRequest(rawBody);

            if (requiresAuthentication(request.type()))
            {
                AuthOutcome outcome = authenticate(request);

                if (outcome.principal().isEmpty())
                {
                    servletResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);

                    return gson.toJson(ResponseEnvelope.failure(
                            request.requestId(),
                            AUTH_FAILURE_TYPE,
                            outcome.errorCode(),
                            outcome.errorMessage()
                    ));
                }

                AuthContext.set(outcome.principal().get());
            }

            ResponseEnvelope response = dispatcher.dispatch(request);

            // Map the standardized error codes onto HTTP statuses; route-specific
            // business failures (and all successes) remain HTTP 200.
            servletResponse.setStatus(ErrorStatusMapper.statusFor(response.errorCode()));

            return gson.toJson(response);
        }
        catch (MalformedRequestException e)
        {
            servletResponse.setStatus(HttpServletResponse.SC_BAD_REQUEST);

            return gson.toJson(ResponseEnvelope.failure(
                    null,
                    ResponseType.BAD_REQUEST.toWire(),
                    "MALFORMED_JSON",
                    e.getMessage()
            ));
        }
        catch (UnknownRequestTypeException e)
        {
            servletResponse.setStatus(HttpServletResponse.SC_BAD_REQUEST);

            return gson.toJson(ResponseEnvelope.failure(
                    null,
                    ResponseType.UNKNOWN_REQUEST.toWire(),
                    "UNKNOWN_REQUEST",
                    e.getMessage()
            ));
        }
        catch (Exception e)
        {
            // Server-side failures - including infrastructure/session-resolver
            // exceptions raised while authenticating - are HTTP 500, never
            // reported as MALFORMED_JSON.
            servletResponse.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);

            return gson.toJson(ResponseEnvelope.failure(
                    null,
                    ResponseType.BAD_REQUEST.toWire(),
                    "UNEXPECTED_ERROR",
                    e.getMessage() != null
                            ? e.getMessage()
                            : "Unexpected server error"
            ));
        }
        finally
        {
            AuthContext.clear();
        }
    }

    /**
     * Parses and validates the raw request body into a {@link RequestEnvelope}.
     *
     * <p>Malformed JSON and unknown request types are deliberately distinguished:
     * a syntactically invalid body is reported as {@code MALFORMED_JSON}, while a
     * well-formed body carrying an unrecognized {@code type} is reported as
     * {@code UNKNOWN_REQUEST}.
     */
    private RequestEnvelope parseRequest(String rawBody)
    {
        JsonObject root;

        try
        {
            root = gson.fromJson(rawBody, JsonObject.class);
        }
        catch (JsonSyntaxException e)
        {
            throw new MalformedRequestException(
                    "Invalid payload format: " + e.getMessage()
            );
        }

        if (root == null)
        {
            throw new MalformedRequestException(
                    "Invalid payload format: request body is missing."
            );
        }

        JsonElement typeElement = root.get("type");

        if (typeElement == null
                || !typeElement.isJsonPrimitive()
                || !typeElement.getAsJsonPrimitive().isString())
        {
            throw new MalformedRequestException(
                    "Invalid payload format: request type is missing."
            );
        }

        String typeValue = typeElement.getAsString();

        if (typeValue.isBlank())
        {
            throw new MalformedRequestException(
                    "Invalid payload format: request type is blank."
            );
        }

        if (RequestType.fromWire(typeValue) == null)
        {
            throw new UnknownRequestTypeException(
                    "Unknown request type: " + typeValue
            );
        }

        try
        {
            return gson.fromJson(rawBody, RequestEnvelope.class);
        }
        catch (JsonSyntaxException e)
        {
            throw new MalformedRequestException(
                    "Invalid payload format: " + e.getMessage()
            );
        }
    }

    /**
     * Signals a syntactically invalid request body ({@code MALFORMED_JSON}).
     */
    private static final class MalformedRequestException extends RuntimeException
    {
        private MalformedRequestException(String message)
        {
            super(message);
        }
    }

    /**
     * Signals a well-formed body carrying an unrecognized request type
     * ({@code UNKNOWN_REQUEST}).
     */
    private static final class UnknownRequestTypeException extends RuntimeException
    {
        private UnknownRequestTypeException(String message)
        {
            super(message);
        }
    }

    private boolean requiresAuthentication(RequestType type)
    {
        return type != null && !ANONYMOUS_REQUEST_TYPES.contains(type);
    }

    private AuthOutcome authenticate(RequestEnvelope request)
    {
        String sessionToken = resolveSessionToken(request);

        if (sessionToken == null)
        {
            return AuthOutcome.missingCredentials();
        }

        Optional<AuthPrincipal> principal =
                sessionPrincipalResolver.resolve(sessionToken);

        if (principal.isEmpty())
        {
            return AuthOutcome.invalidCredentials();
        }

        return AuthOutcome.authenticated(principal);
    }

    /**
     * Extracts the session credential from the request. Priority is:
     *
     * <ol>
     *   <li>payload {@code sessionToken} - the field the application-layer use cases
     *       validate via {@code AuthLockOrchestrator};</li>
     *   <li>payload {@code token} - used by the few request DTOs that name the field
     *       {@code token} (e.g. {@code GetRepliesRequest});</li>
     *   <li>envelope-level {@code token} - the transport-level field the socket client
     *       attaches ({@code RequestEnvelope.withToken}).</li>
     * </ol>
     */
    private String resolveSessionToken(RequestEnvelope request)
    {
        if (request.payload() != null && request.payload().isJsonObject())
        {
            JsonObject payload = request.payload().getAsJsonObject();

            String payloadSessionToken = stringMember(payload, "sessionToken");
            if (payloadSessionToken != null)
            {
                return payloadSessionToken;
            }

            String payloadToken = stringMember(payload, "token");
            if (payloadToken != null)
            {
                return payloadToken;
            }
        }

        return request.token();
    }

    private String stringMember(JsonObject object, String memberName)
    {
        JsonElement element = object.get(memberName);

        if (element == null
                || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString())
        {
            return null;
        }

        String value = element.getAsString();
        return (value == null || value.isBlank()) ? null : value;
    }

    private record AuthOutcome(
            Optional<AuthPrincipal> principal,
            String errorCode,
            String errorMessage
    )
    {
        static AuthOutcome missingCredentials()
        {
            return new AuthOutcome(
                    Optional.empty(),
                    AUTH_REQUIRED_CODE,
                    "Authentication is required for this operation."
            );
        }

        static AuthOutcome invalidCredentials()
        {
            return new AuthOutcome(
                    Optional.empty(),
                    UNAUTHORIZED_CODE,
                    "Session not found or invalid."
            );
        }

        static AuthOutcome authenticated(Optional<AuthPrincipal> principal)
        {
            return new AuthOutcome(principal, null, null);
        }
    }
}