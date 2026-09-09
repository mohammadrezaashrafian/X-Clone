package logic_core.app.service.email;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Renders the maintainable email templates (Issue #20).
 *
 * <p>Templates live under {@code classpath:email/{type}.html} and
 * {@code classpath:email/{type}.txt} and are loaded once at startup.
 * Placeholders are simple {@code {name}} tokens replaced from the message
 * variables. The HTML body escapes every substituted value so email content
 * can never be broken out by user-controlled input. Plain text is not
 * HTML-escaped (it is plain text).
 *
 * <p>No templating framework is introduced — the template set is small and
 * fixed, and this keeps the dependency footprint at zero.
 */
@Component
public class EmailTemplateRenderer
{
    private static final String PLACEHOLDER_PREFIX = "{";
    private static final String PLACEHOLDER_SUFFIX = "}";

    private final Map<String, String> htmlTemplates;
    private final Map<String, String> textTemplates;

    public EmailTemplateRenderer()
    {
        this.htmlTemplates = loadAll("html");
        this.textTemplates = loadAll("txt");
    }

    public String renderHtml(EmailMessageType type, Map<String, String> variables)
    {
        return substitute(htmlTemplates.get(type.templateBaseName()), variables, true);
    }

    public String renderText(EmailMessageType type, Map<String, String> variables)
    {
        return substitute(textTemplates.get(type.templateBaseName()), variables, false);
    }

    private Map<String, String> loadAll(String extension)
    {
        Map<String, String> templates = new java.util.HashMap<>();
        for (EmailMessageType type : EmailMessageType.values())
        {
            String name = "email/" + type.templateBaseName() + "." + extension;
            try
            {
                templates.put(
                        type.templateBaseName(),
                        new String(
                                new ClassPathResource(name).getInputStream().readAllBytes(),
                                StandardCharsets.UTF_8)
                );
            }
            catch (IOException e)
            {
                throw new IllegalStateException("Missing email template: " + name, e);
            }
        }
        return templates;
    }

    private static String substitute(
            String template,
            Map<String, String> variables,
            boolean htmlEscape)
    {
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet())
        {
            String token = PLACEHOLDER_PREFIX + entry.getKey() + PLACEHOLDER_SUFFIX;
            String value = entry.getValue() == null ? "" : entry.getValue();
            if (htmlEscape)
            {
                value = escapeHtml(value);
            }
            result = result.replace(token, value);
        }
        return result;
    }

    /** Minimal HTML escaping for the small, fixed placeholder set. */
    private static String escapeHtml(String value)
    {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}