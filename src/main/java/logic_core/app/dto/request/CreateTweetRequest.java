package logic_core.app.dto.request;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record CreateTweetRequest(
        String content,
        UUID replyToId,
        UUID quoteOfId,
        OffsetDateTime scheduledAt,
        String sessionToken,
        List<String> mediaUrls,
        PollRequest poll
) {
    /**
     * Convenience constructor for callers without a poll, preserving the
     * pre-poll 6-argument signature so existing call sites are unaffected.
     */
    public CreateTweetRequest(
            String content,
            UUID replyToId,
            UUID quoteOfId,
            OffsetDateTime scheduledAt,
            String sessionToken,
            List<String> mediaUrls)
    {
        this(content, replyToId, quoteOfId, scheduledAt, sessionToken, mediaUrls, null);
    }
}