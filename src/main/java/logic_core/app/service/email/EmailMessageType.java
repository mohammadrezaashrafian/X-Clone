package logic_core.app.service.email;

/**
 * Supported authentication-related email kinds (Issue #20).
 *
 * <p>Each type maps to an HTML + plain-text template pair under
 * {@code classpath:email/}.
 */
public enum EmailMessageType
{
    PASSWORD_RESET("password-reset"),
    EMAIL_VERIFICATION("email-verification"),
    EMAIL_CHANGE("email-change");

    private final String templateBaseName;

    EmailMessageType(String templateBaseName)
    {
        this.templateBaseName = templateBaseName;
    }

    public String templateBaseName()
    {
        return templateBaseName;
    }
}