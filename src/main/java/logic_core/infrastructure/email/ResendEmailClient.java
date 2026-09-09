package logic_core.infrastructure.email;

import logic_core.app.service.email.EmailDeliveryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Thin, provider-specific HTTP client for the Resend REST API (Issue #20).
 * Infrastructure-only: nothing above {@link EmailDeliveryPort} knows it
 * exists.
 *
 * <p>Low-level behavior: sends {@code POST {apiUrl}} with the
 * {@code Authorization: Bearer <key>} header and a JSON body, treats any
 * non-2xx (429 quota/rate-limit included) as an {@link EmailDeliveryException}
 * with a safe message. Never logs the API key, headers, or raw provider
 * response bodies.
 */
public class ResendEmailClient
{
    private static final Logger log = LoggerFactory.getLogger(ResendEmailClient.class);

    private final RestClient restClient;
    private final String apiKey;

    public ResendEmailClient(String apiUrl, String apiKey)
    {
        this.apiKey = apiKey == null ? "" : apiKey;
        this.restClient = RestClient.builder()
                .baseUrl(apiUrl)
                .build();
    }

    public void send(String from, String to, String subject, String html, String text)
    {
        if (apiKey.isBlank())
        {
            throw new EmailDeliveryException(
                    "Resend is not configured (EMAIL_PROVIDER=resend requires RESEND_API_KEY).");
        }

        Map<String, Object> body = Map.of(
                "from", from,
                "to", List.of(to),
                "subject", subject,
                "html", html,
                "text", text
        );

        HttpStatusCode status = restClient.post()
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (request, response) ->
                {
                    // Safe message only; response body is never propagated or logged.
                    throw new EmailDeliveryException(
                            "Email provider rejected the request (HTTP " + response.getStatusCode().value() + ").");
                })
                .toBodilessEntity()
                .getStatusCode();

        log.debug("resend email accepted (HTTP {})", status.value());
    }
}