package logic_core.app.service.email;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Template generation (Issue #20): HTML + plain-text pairs render the
 * placeholders, HTML is escaped, and no business-logic string blobs exist.
 */
class EmailTemplateRendererTest
{
    private final EmailTemplateRenderer renderer = new EmailTemplateRenderer();

    @Test
    void passwordReset_rendersHtmlAndText_withCodeAndExpiry()
    {
        Map<String, String> vars = Map.of(
                "code", "481516",
                "email", "alice@example.com",
                "appName", "X-Clone",
                "expiresInMinutes", "10");

        String html = renderer.renderHtml(EmailMessageType.PASSWORD_RESET, vars);
        String text = renderer.renderText(EmailMessageType.PASSWORD_RESET, vars);

        assertThat(html).contains("481516").contains("alice@example.com").contains("10");
        assertThat(html).contains("<html").contains("<body");
        assertThat(text).contains("481516").contains("alice@example.com").contains("10");
        assertThat(text).doesNotContain("<html").doesNotContain("<body");
    }

    @Test
    void allThreeTypes_renderBothVariants()
    {
        for (EmailMessageType type : EmailMessageType.values())
        {
            Map<String, String> vars = Map.of(
                    "code", "123456",
                    "email", "bob@example.com",
                    "newEmail", "new@example.com",
                    "appName", "X-Clone",
                    "expiresInMinutes", "10");

            assertThat(renderer.renderHtml(type, vars)).isNotBlank();
            assertThat(renderer.renderText(type, vars)).isNotBlank();
        }
    }

    @Test
    void htmlBody_escapesUserControlledValues()
    {
        Map<String, String> vars = Map.of(
                "code", "<script>alert(1)</script>",
                "email", "a&b@example.com",
                "appName", "X\"Clone'",
                "expiresInMinutes", "10");

        String html = renderer.renderHtml(EmailMessageType.EMAIL_VERIFICATION, vars);

        assertThat(html)
                .doesNotContain("<script>")
                .contains("&lt;script&gt;")
                .contains("a&amp;b@example.com")
                .contains("X&quot;Clone&#39;");

        // Plain text is not HTML-escaped.
        String text = renderer.renderText(EmailMessageType.EMAIL_VERIFICATION, vars);
        assertThat(text).contains("<script>alert(1)</script>");
    }
}