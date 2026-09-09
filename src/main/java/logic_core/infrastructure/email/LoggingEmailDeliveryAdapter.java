package logic_core.infrastructure.email;

import logic_core.app.service.email.EmailDeliveryPort;
import logic_core.app.service.email.EmailMessage;
import logic_core.app.service.email.EmailTemplateRenderer;

import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Development-only {@link EmailDeliveryPort} (Issue #20).
 *
 * <p>Used when {@code app.email.provider=log} (the default). Prints the
 * rendered email to stdout so local development works without real
 * credentials. This adapter deliberately prints the out-of-band code — that is
 * its entire purpose in local development. It is <b>never</b> selected in a
 * production configuration, and production adapters never log codes.
 *
 * <p>Keeps a bounded in-memory record of recent deliveries so integration
 * tests can assert delivery deterministically without the internet.
 */
public class LoggingEmailDeliveryAdapter implements EmailDeliveryPort
{
    private static final int MAX_RECORDED = 100;

    private final EmailTemplateRenderer renderer;
    private final ConcurrentLinkedDeque<EmailMessage> recorded = new ConcurrentLinkedDeque<>();

    public LoggingEmailDeliveryAdapter(EmailTemplateRenderer renderer)
    {
        this.renderer = renderer;
    }

    @Override
    public void send(EmailMessage message)
    {
        String html = renderer.renderHtml(message.type(), message.variables());
        String text = renderer.renderText(message.type(), message.variables());

        System.out.println("[EMAIL_DEV][" + message.type() + "] to=" + message.to());
        System.out.println("[EMAIL_DEV] subject=" + subjectFor(message.type()));
        System.out.println("[EMAIL_DEV] --- text ---");
        System.out.println(text);
        System.out.println("[EMAIL_DEV] --- html (" + html.length() + " chars) ---");

        recorded.addLast(message);
        while (recorded.size() > MAX_RECORDED)
        {
            recorded.removeFirst();
        }
    }

    /** Recent deliveries recorded by this adapter (bounded). */
    public java.util.List<EmailMessage> recordedDeliveries()
    {
        return java.util.List.copyOf(recorded);
    }

    public void clear()
    {
        recorded.clear();
    }

    private static String subjectFor(logic_core.app.service.email.EmailMessageType type)
    {
        return switch (type)
        {
            case PASSWORD_RESET -> "Your password reset code";
            case EMAIL_VERIFICATION -> "Verify your email";
            case EMAIL_CHANGE -> "Confirm your new email";
        };
    }
}