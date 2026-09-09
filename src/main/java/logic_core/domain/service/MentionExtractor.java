package logic_core.domain.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure extraction of {@code @username} mention tokens from tweet content.
 *
 * <p>The token grammar mirrors the application's username convention
 * ({@code ^[a-zA-Z0-9_-]{3,20}$}): an {@code @} followed by 3–20 letters,
 * digits, underscores or hyphens. Tokens that cannot be a valid username are
 * simply not mentions — arbitrary {@code @} text is never treated as a
 * mention, and an email address like {@code user@example.com} only produces a
 * candidate when {@code example} happens to be a valid username token.
 *
 * <p>Usernames in this application are case-sensitive identifiers (registration
 * stores them exactly as provided), so tokens are NOT lowercased here; the
 * extracted token is resolved verbatim against the user registry. Duplicate
 * tokens within one tweet are collapsed.
 *
 * <p>This is a stateless utility shared by tweet creation/reply (extraction)
 * and any future consumer of mention relationships. Resolution against real
 * users is the responsibility of the caller.
 */
public final class MentionExtractor
{
    private static final Pattern MENTION_PATTERN =
            Pattern.compile("@([a-zA-Z0-9_-]{3,20})");

    private MentionExtractor()
    {
    }

    /**
     * Extracts distinct mention tokens from {@code content}, preserving
     * first-seen order. Duplicate mentions within the tweet are collapsed.
     *
     * @return the distinct tokens (never {@code null})
     */
    public static List<String> extract(String content)
    {
        if (content == null || content.isBlank())
        {
            return List.of();
        }

        Set<String> usernames = new LinkedHashSet<>();

        Matcher matcher = MENTION_PATTERN.matcher(content);

        while (matcher.find())
        {
            usernames.add(matcher.group(1));
        }

        return List.copyOf(usernames);
    }
}