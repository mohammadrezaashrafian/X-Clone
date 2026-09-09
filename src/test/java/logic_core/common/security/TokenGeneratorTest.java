package logic_core.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session-token entropy (Issue #21 security baseline).
 *
 * <p>Session tokens are the sole bearer credential for every protected
 * operation; they must carry real cryptographic entropy (not the 122 bits of
 * a random UUID, and never a predictable source) and be collision-free.
 */
@DisplayName("TokenGenerator security tests")
class TokenGeneratorTest
{
    private final TokenGenerator tokenGenerator = new TokenGenerator();

    @Test
    @DisplayName("tokens carry 256 bits of entropy (43 Base64url chars, no padding)")
    void token_hasFullEntropyEncoding()
    {
        String token = tokenGenerator.generateToken();

        assertThat(token)
                .as("token length pins the entropy: 32 bytes -> 43 Base64url chars")
                .hasSize(43);
        assertThat(token).doesNotContain("=").doesNotContain("+").doesNotContain("/");
        // Fits the sessions.token varchar(255) column without any migration.
        assertThat(token.length()).isLessThanOrEqualTo(255);

        byte[] decoded = Base64.getUrlDecoder().decode(token);
        assertThat(decoded).as("32 decoded bytes = 256 bits of entropy").hasSize(32);
    }

    @Test
    @DisplayName("tokens are uniformly distributed and collision-free across many draws")
    void tokens_areUniqueAcrossManyDraws()
    {
        int sampleSize = 5_000;
        Set<String> tokens = new HashSet<>(sampleSize * 2);
        int[] byteCounts = new int[256];

        for (int i = 0; i < sampleSize; i++)
        {
            String token = tokenGenerator.generateToken();
            assertThat(tokens.add(token))
                    .as("no collisions in %d draws", sampleSize)
                    .isTrue();

            for (byte b : Base64.getUrlDecoder().decode(token))
            {
                byteCounts[b & 0xFF]++;
            }
        }

        // A degenerate/predictable source would cluster byte values. For a
        // uniform generator each byte value averages sampleSize*32/256 = 625
        // occurrences; nothing may deviate by more than 8 sigma (~200).
        int expected = sampleSize * 32 / 256;
        for (int i = 0; i < 256; i++)
        {
            assertThat(Math.abs(byteCounts[i] - expected))
                    .as("byte value %d deviates from uniformity", i)
                    .isLessThanOrEqualTo(200);
        }
    }
}
