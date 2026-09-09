package logic_core.infrastructure.email;

import logic_core.app.service.email.EmailDeliveryException;
import logic_core.app.service.email.EmailDeliveryPort;
import logic_core.app.service.email.EmailMessage;
import logic_core.app.service.email.EmailMessageType;
import logic_core.app.service.email.EmailTemplateRenderer;

import java.util.Map;

/**
 * Real email delivery through the Resend REST API (Issue #20).
 *
 * <p>Chosen over SMTP: no mail server, no {@code spring-boot-starter-mail} /
 * JavaMail dependency, no outbound SMTP port — just a JSON POST to
 * {@code POST https://api.resend.com/emails} using Spring's existing
 * {@code RestClient} (spring-web, already on the classpath). This keeps the
 * dependency footprint at zero and the provider fully replaceable behind
 * {@link EmailDeliveryPort}.
 *
 * <p><b>Free-tier no-domain path:</b> with {@code EMAIL_FROM} set to the
 * account's {@code onboarding@resend.dev} sender, Resend delivers to the
 * account owner's own verified address — sufficient for local/portfolio use
 * without buying a domain. When a custom domain is verified later, only the
 * {@code EMAIL_FROM} value changes; no code changes are required.
 *
 * <p><b>Security:</b> credentials and authorization headers are never
 * logged; provider error bodies are never propagated to clients. Any
 * failure raises {@link EmailDeliveryException} with a safe message.
 */
public class ResendEmailDeliveryAdapter implements EmailDeliveryPort
{
    private static final Map<EmailMessageType, String> SUBJECTS = Map.of(
            EmailMessageType.PASSWORD_RESET, "Your password reset code",
            EmailMessageType.EMAIL_VERIFICATION, "Verify your email",
            EmailMessageType.EMAIL_CHANGE, "Confirm your new email"
    );

    private final ResendEmailClient client;
    private final EmailTemplateRenderer renderer;
    private final String fromAddress;

    public ResendEmailDeliveryAdapter(
            ResendEmailClient client,
            EmailTemplateRenderer renderer,
            String fromAddress)
    {
        this.client = client;
        this.renderer = renderer;
        this.fromAddress = fromAddress;
    }

    @Override
    public void send(EmailMessage message)
    {
        String html = renderer.renderHtml(message.type(), message.variables());
        String text = renderer.renderText(message.type(), message.variables());

        try
        {
            client.send(
                    fromAddress,
                    message.to(),
                    SUBJECTS.get(message.type()),
                    html,
                    text
            );
        }
        catch (EmailDeliveryException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            // Never leak provider internals; the cause is only logged by the
            // calling service with safe diagnostics.
            throw new EmailDeliveryException("Email could not be delivered.", e);
        }
    }
}