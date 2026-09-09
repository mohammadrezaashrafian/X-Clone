package logic_core.common.security;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Generates session-token material.
 *
 * <p>Issue #21 security baseline: tokens are {@code 256 bit} of
 * {@link SecureRandom} entropy, Base64url-encoded without padding (43 ASCII
 * characters) — a ~256-bit uniform secret instead of a 122-bit UUID. Fits the
 * existing {@code sessions.token varchar(255)} column and the unique index,
 * so no migration is required. Tokens remain opaque, unguessable and are
 * never logged anywhere in the codebase.
 */
@Component
public class TokenGenerator
{
    private static final int TOKEN_BYTES = 32; // 256-bit entropy

    private final SecureRandom secureRandom = new SecureRandom();

    public String generateToken()
    {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
