package logic_core.app.dto.validator;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Validates login credentials. The identifier may be either a username or an
 * email address (the login field advertises "Username or email"): inputs
 * containing '@' are validated as emails, everything else as usernames.
 */
@Component
@RequiredArgsConstructor
public class LoginValidator
{
    private final UsernameValidator usernameValidator;
    private final PasswordValidator passwordValidator;
    private final EmailValidator emailValidator;

    public void validate(String identifier, String password)
    {
        if (identifier != null && identifier.contains("@"))
        {
            emailValidator.validate(identifier);
        }
        else
        {
            usernameValidator.validate(identifier);
        }

        passwordValidator.validate(password);
    }

}