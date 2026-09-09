package logic_core.infrastructure.email;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Environment-driven email configuration (Issue #20).
 *
 * <p>All values come from environment variables with safe local defaults.
 * <b>Never commit credentials.</b> The API key default is empty; when
 * {@code app.email.provider=resend} requires a key that is absent, the
 * Resend adapter fails safely (no client-visible provider errors, no
 * credentials in logs).
 *
 * <ul>
 *   <li>{@code EMAIL_PROVIDER} — {@code log} (default, local dev) or {@code resend}</li>
 *   <li>{@code EMAIL_FROM} — sender; on the free tier without a verified domain
 *       Resend requires the account's own {@code onboarding@resend.dev} address</li>
 *   <li>{@code RESEND_API_KEY} — outbound API key, kept outside source control</li>
 *   <li>{@code RESEND_API_URL} — overridable endpoint (defaults to production)</li>
 * </ul>
 */
@Component
public class EmailProperties
{
    private final String provider;
    private final String fromAddress;
    private final String resendApiKey;
    private final String resendApiUrl;

    public EmailProperties(
            @Value("${app.email.provider:log}") String provider,
            @Value("${app.email.from:X-Clone <onboarding@resend.dev>}") String fromAddress,
            @Value("${RESEND_API_KEY:}") String resendApiKey,
            @Value("${RESEND_API_URL:https://api.resend.com/emails}") String resendApiUrl)
    {
        this.provider = provider == null ? "log" : provider.trim().toLowerCase(java.util.Locale.ROOT);
        this.fromAddress = fromAddress;
        this.resendApiKey = resendApiKey == null ? "" : resendApiKey;
        this.resendApiUrl = resendApiUrl;
    }

    public boolean isResendEnabled()
    {
        return "resend".equals(provider);
    }

    public String getFromAddress()
    {
        return fromAddress;
    }

    public String getResendApiKey()
    {
        return resendApiKey;
    }

    public String getResendApiUrl()
    {
        return resendApiUrl;
    }
}