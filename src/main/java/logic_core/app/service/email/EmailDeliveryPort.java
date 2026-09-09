package logic_core.app.service.email;

/**
 * Application-level outgoing-email port (Issue #20).
 *
 * <p>Use cases depend on this interface only. Provider-specific technology
 * (Resend API, SMTP, logging) lives in infrastructure adapters, so the
 * provider can be swapped without touching application/domain code.
 *
 * <p>Failure contract: implementations must not throw. A provider/network
 * failure is caught internally, logged with safe operational diagnostics
 * (never credentials, never raw codes), and surfaced as a
 * {@link EmailDeliveryException} to the caller so the application layer can
 * decide how to degrade.
 */
public interface EmailDeliveryPort
{
    /**
     * Renders and delivers {@code message}.
     *
     * @throws EmailDeliveryException when delivery could not be completed;
     *         the exception message must never contain provider secrets or
     *         sensitive data
     */
    void send(EmailMessage message);
}