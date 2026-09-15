package Client;

public final class PasswordResetContext
{
    /** Distinguishes password-reset verification from registration email verification. */
    public enum VerificationMode { PASSWORD_RESET, REGISTRATION }

    private static final PasswordResetContext INSTANCE = new PasswordResetContext();

    private String email;
    private String code;
    private VerificationMode verificationMode;

    private PasswordResetContext()
    {
    }

    public static PasswordResetContext getInstance()
    {
        return INSTANCE;
    }

    public synchronized String getEmail()
    {
        return email;
    }

    public synchronized void setEmail(String email)
    {
        this.email = normalize(email);
    }

    public synchronized String getCode()
    {
        return code;
    }

    public synchronized void setCode(String code)
    {
        this.code = normalize(code);
    }

    public synchronized VerificationMode getVerificationMode()
    {
        return verificationMode;
    }

    public synchronized void setVerificationMode(VerificationMode mode)
    {
        this.verificationMode = mode;
    }

    public synchronized void clear()
    {
        email = null;
        code = null;
        verificationMode = null;
    }

    public synchronized boolean hasEmail()
    {
        return email != null && !email.isBlank();
    }

    public synchronized boolean hasCode()
    {
        return code != null && !code.isBlank();
    }

    private String normalize(String value)
    {
        if (value == null)
        {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
