package logic_core.app.service.email;

import java.util.Map;

/**
 * One email to deliver (Issue #20).
 *
 * <p>Application-level value object: carries the recipient, the message kind
 * and the template variables. It deliberately contains <b>no</b> resolved
 * HTML/plain-text bodies and no provider-specific fields; rendering and
 * transport are the delivery adapter's concern.
 *
 * <p>Security: template variables may include non-sensitive values such as
 * the user's address; the raw OTP/code is {@code code} only for the
 * recipient's email — it must never be logged by any layer.
 */
public record EmailMessage(
        String to,
        EmailMessageType type,
        Map<String, String> variables
)
{
    public EmailMessage
    {
        if (to == null || to.isBlank())
        {
            throw new IllegalArgumentException("email recipient is required");
        }
        if (type == null)
        {
            throw new IllegalArgumentException("email type is required");
        }
        if (variables == null)
        {
            throw new IllegalArgumentException("email variables are required");
        }
    }
}