package logic_core.app.service.email;

/**
 * Raised by {@link EmailDeliveryPort} adapters when an email could not be
 * delivered (provider/network failure). Deliberately carries no provider
 * details and never carries credentials or raw codes, so it is safe to log
 * and to expose to application code.
 */
public class EmailDeliveryException extends RuntimeException
{
    public EmailDeliveryException(String message)
    {
        super(message);
    }

    public EmailDeliveryException(String message, Throwable cause)
    {
        super(message, cause);
    }
}