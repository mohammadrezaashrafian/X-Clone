package logic_core.infrastructure.transport.http;

import com.google.gson.Gson;
import logic_core.infrastructure.transport.ResponseEnvelope;
import logic_core.infrastructure.transport.ResponseType;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Converts Spring framework-level HTTP failures into the standard
 * {@link ResponseEnvelope} so the whole API speaks one error contract.
 *
 * <p>Only failures that never reach {@link HttpTransportController} are handled
 * here: unreadable/empty request bodies (raised during argument resolution,
 * before the controller method runs) and unsupported media types. Domain and
 * application failures are already converted to envelopes by the controller and
 * {@link logic_core.infrastructure.transport.server.RequestDispatcher}.
 */
@RestControllerAdvice
@RequiredArgsConstructor
public class ApiTransportExceptionHandler
{
    private final Gson gson;

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<String> handleUnreadableBody(
            HttpMessageNotReadableException e
    )
    {
        return envelope(
                HttpStatus.BAD_REQUEST,
                ResponseType.BAD_REQUEST.toWire(),
                "MALFORMED_JSON",
                "Invalid payload format: " + e.getMessage()
        );
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<String> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException e
    )
    {
        return envelope(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                ResponseType.BAD_REQUEST.toWire(),
                "UNSUPPORTED_MEDIA_TYPE",
                "Unsupported media type: " + e.getContentType()
        );
    }

    private ResponseEntity<String> envelope(
            HttpStatus status,
            String type,
            String errorCode,
            String errorMessage
    )
    {
        return ResponseEntity
                .status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(gson.toJson(ResponseEnvelope.failure(
                        null,
                        type,
                        errorCode,
                        errorMessage
                )));
    }
}