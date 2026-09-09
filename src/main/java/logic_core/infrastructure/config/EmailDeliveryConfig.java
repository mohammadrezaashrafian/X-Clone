package logic_core.infrastructure.config;

import logic_core.app.service.email.EmailDeliveryPort;
import logic_core.app.service.email.EmailTemplateRenderer;
import logic_core.infrastructure.email.EmailProperties;
import logic_core.infrastructure.email.LoggingEmailDeliveryAdapter;
import logic_core.infrastructure.email.ResendEmailClient;
import logic_core.infrastructure.email.ResendEmailDeliveryAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Email delivery adapter selection (Issue #20), entirely environment-driven:
 *
 * <ul>
 *   <li>{@code app.email.provider=log} (default) — dev logging adapter; no
 *       credentials required, local development and tests work offline.</li>
 *   <li>{@code app.email.provider=resend} — real delivery through the Resend
 *       REST API; credentials come from the environment
 *       ({@code RESEND_API_KEY}), never from source control.</li>
 * </ul>
 *
 * <p>Application code depends only on {@link EmailDeliveryPort}, so the
 * provider can be replaced without touching domain/use-case behavior.
 */
@Configuration
public class EmailDeliveryConfig
{
    @Bean
    @ConditionalOnProperty(name = "app.email.provider", havingValue = "resend")
    public EmailDeliveryPort resendEmailDeliveryPort(
            EmailProperties properties,
            EmailTemplateRenderer renderer)
    {
        ResendEmailClient client =
                new ResendEmailClient(properties.getResendApiUrl(), properties.getResendApiKey());
        return new ResendEmailDeliveryAdapter(client, renderer, properties.getFromAddress());
    }

    @Bean
    @ConditionalOnMissingBean(EmailDeliveryPort.class)
    public EmailDeliveryPort loggingEmailDeliveryPort(EmailTemplateRenderer renderer)
    {
        return new LoggingEmailDeliveryAdapter(renderer);
    }
}