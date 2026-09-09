package logic_core.domain.service;

import logic_core.common.exception.ValidationException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure extraction and normalization of hashtags from tweet content.
 *
 * <p>Hashtags are derived from the tweet content itself — the client never
 * supplies hashtag identifiers. A hashtag is a {@code #} followed by one or
 * more letters, digits or underscores. Normalization lowercases the tag and
 * caps it at the {@code hashtags.tag} column length ({@value #MAX_TAG_LENGTH}).
 * Duplicate hashtags within a single tweet are collapsed into one entry whose
 * count reflects how many times the tag appeared.
 *
 * <p>This is a stateless utility shared by tweet creation (extraction) and the
 * hashtag follow/feed use cases (canonical form of a caller-supplied tag).
 */
public final class HashtagExtractor
{
    public static final int MAX_TAG_LENGTH = 100;

    private static final Pattern HASHTAG_PATTERN = Pattern.compile("#([A-Za-z0-9_]+)");
    private static final Pattern VALID_TAG_PATTERN = Pattern.compile("[a-z0-9_]+");

    private HashtagExtractor()
    {
    }

    /**
     * Extracts distinct normalized hashtags from {@code content}, preserving
     * first-seen order. Duplicates within the tweet are collapsed.
     *
     * @return the distinct canonical tags (never {@code null})
     */
    public static List<String> extractNormalized(String content)
    {
        return List.copyOf(extractWithCounts(content).keySet());
    }

    /**
     * Extracts hashtags with their occurrence count inside the tweet, keyed by
     * the normalized tag. The count is the number of times the hashtag matched
     * in the content (1 for a single mention, &gt;1 for repeated mentions).
     */
    public static Map<String, Integer> extractWithCounts(String content)
    {
        Map<String, Integer> counts = new LinkedHashMap<>();

        if (content == null || content.isBlank())
        {
            return counts;
        }

        Matcher matcher = HASHTAG_PATTERN.matcher(content);

        while (matcher.find())
        {
            String raw = matcher.group(1);
            String tag = normalizeToken(raw);

            if (!tag.isEmpty())
            {
                counts.merge(tag, 1, Integer::sum);
            }
        }

        return counts;
    }

    /**
     * Canonical form of a caller-supplied hashtag value (follow/unfollow/feed
     * requests). A leading {@code #} is stripped, the value is lowercased and
     * truncated to the column length.
     *
     * @throws ValidationException when the value is not a valid hashtag
     */
    public static String normalize(String rawTag)
    {
        if (rawTag == null || rawTag.isBlank())
        {
            throw new ValidationException("hashtag.tag.required");
        }

        String cleaned = rawTag.trim();

        if (cleaned.startsWith("#"))
        {
            cleaned = cleaned.substring(1);
        }

        cleaned = cleaned.toLowerCase(Locale.ROOT);

        if (!VALID_TAG_PATTERN.matcher(cleaned).matches())
        {
            throw new ValidationException("hashtag.tag.invalid");
        }

        return normalizeToken(cleaned);
    }

    private static String normalizeToken(String raw)
    {
        String tag = raw.toLowerCase(Locale.ROOT);

        if (tag.length() > MAX_TAG_LENGTH)
        {
            tag = tag.substring(0, MAX_TAG_LENGTH);
        }

        return tag;
    }
}