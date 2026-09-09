package logic_core.app.dto.response;

import java.util.List;

/**
 * Paginated page of hashtag search results, ordered deterministically by the
 * canonical tag ascending.
 */
public record HashtagSearchResponse(
        List<HashtagSearchItem> hashtags,
        long totalItems,
        int page,
        int pageSize,
        boolean hasNext
) {
    /**
     * One matched hashtag — the canonical persisted tag value (lowercase).
     */
    public record HashtagSearchItem(
            String tag
    ) {}
}
