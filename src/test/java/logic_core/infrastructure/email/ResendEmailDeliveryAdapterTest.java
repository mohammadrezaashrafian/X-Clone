package logic_core.infrastructure.email;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import logic_core.app.service.email.EmailDeliveryException;
import logic_core.app.service.email.EmailMessage;
import logic_core.app.service.email.EmailMessageType;
import logic_core.app.service.email.EmailTemplateRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real delivery adapter at the infrastructure boundary (Issue #20), tested
 * against an <b>in-process stub HTTP server</b> — no internet, no real API
 * key, no inbox. Verifies the wire contract (method, path, auth header, JSON
 * body) and safe failure handling (non-2xx / missing key), and that the
 * adapter never leaks credentials or provider bodies.
 */
class ResendEmailDeliveryAdapterTest
{
    private HttpServer server;
    private String apiUrl;

    private void startServer(int statusCode, String responseBody) throws IOException
    {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        apiUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/emails";
        createContext(statusCode, responseBody);
        server.start();
    }

    private void createContext(int statusCode, String responseBody)
    {
        server.createContext("/emails", exchange ->
        {
            try
            {
                handle(exchange, statusCode, responseBody);
            }
            catch (Exception e)
            {
                exchange.close();
            }
        });
    }

    private final AtomicReference<String> lastAuthorization = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    private void handle(HttpExchange exchange, int statusCode, String body)
    {
        try
        {
            lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, response.length);
            try (OutputStream os = exchange.getResponseBody())
            {
                os.write(response);
            }
        }
        catch (IOException e)
        {
            throw new IllegalStateException(e);
        }
    }

    @AfterEach
    void tearDown()
    {
        if (server != null)
        {
            server.stop(0);
        }
    }

    private ResendEmailDeliveryAdapter adapter(String apiKey)
    {
        ResendEmailClient client = new ResendEmailClient(apiUrl, apiKey);
        return new ResendEmailDeliveryAdapter(
                client,
                new EmailTemplateRenderer(),
                "X-Clone <onboarding@resend.dev>");
    }

    @Test
    void sendsJsonToProvider_withAuthHeaderAndRenderedBodies() throws IOException
    {
        startServer(200, "{\"id\":\"abc\"}");

        ResendEmailDeliveryAdapter adapter = adapter("re_secret_key_123");

        adapter.send(new EmailMessage(
                "alice@example.com",
                EmailMessageType.PASSWORD_RESET,
                Map.of("code", "123456", "email", "alice@example.com",
                        "appName", "X-Clone", "expiresInMinutes", "10")));

        assertThat(lastAuthorization.get()).isEqualTo("Bearer re_secret_key_123");
        assertThat(lastBody.get())
                .contains("\"to\":[\"alice@example.com\"]")
                .contains("\"from\":\"X-Clone <onboarding@resend.dev>\"")
                .contains("\"subject\":\"Your password reset code\"")
                .contains("\"html\"")
                .contains("\"text\"")
                .contains("123456");
    }

    @Test
    void providerError_raisesSafeException_withoutLeakingDetails() throws IOException
    {
        startServer(429, "{\"message\":\"rate limit exceeded\",\"details\":\"internal secrets\"}");

        ResendEmailDeliveryAdapter adapter = adapter("re_key");

        assertThatThrownBy(() -> adapter.send(new EmailMessage(
                "alice@example.com",
                EmailMessageType.EMAIL_VERIFICATION,
                Map.of("code", "123456", "email", "alice@example.com",
                        "appName", "X-Clone", "expiresInMinutes", "10"))))
                .isInstanceOf(EmailDeliveryException.class)
                .hasMessageContaining("HTTP 429")
                .hasMessageNotContaining("rate limit exceeded")
                .hasMessageNotContaining("internal secrets");
    }

    @Test
    void missingApiKey_failsSafelyWithoutNetworkCalls()
    {
        // No server started: if the adapter attempted a call it would throw
        // a connection error; instead it must fail on the blank key first.
        ResendEmailClient client = new ResendEmailClient("http://127.0.0.1:1/emails", "  ");
        ResendEmailDeliveryAdapter adapter = new ResendEmailDeliveryAdapter(
                client,
                new EmailTemplateRenderer(),
                "from@example.com");

        assertThatThrownBy(() -> adapter.send(new EmailMessage(
                "alice@example.com",
                EmailMessageType.EMAIL_CHANGE,
                Map.of("code", "123456", "newEmail", "alice@example.com",
                        "appName", "X-Clone", "expiresInMinutes", "10"))))
                .isInstanceOf(EmailDeliveryException.class)
                .hasMessageContaining("RESEND_API_KEY");
    }

    @Test
    void networkFailure_raisesSafeException()
    {
        // Nothing listens on this port.
        ResendEmailClient client = new ResendEmailClient("http://127.0.0.1:1/emails", "re_key");
        ResendEmailDeliveryAdapter adapter = new ResendEmailDeliveryAdapter(
                client,
                new EmailTemplateRenderer(),
                "from@example.com");

        assertThatThrownBy(() -> adapter.send(new EmailMessage(
                "alice@example.com",
                EmailMessageType.PASSWORD_RESET,
                Map.of("code", "123456", "email", "alice@example.com",
                        "appName", "X-Clone", "expiresInMinutes", "10"))))
                .isInstanceOf(EmailDeliveryException.class)
                .hasMessageNotContaining("re_key");
    }
}