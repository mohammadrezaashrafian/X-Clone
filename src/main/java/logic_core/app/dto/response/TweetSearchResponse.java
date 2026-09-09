package logic_core.app.dto.response;

import logic_core.app.dto.timeline.TimelineTweet;
import lombok.Builder;

import java.util.List;

/**
 * Paginated page of tweet-content search results, newest first
 * (deterministically tie-broken by tweet id ascending).
 */
@Builder
public record TweetSearchResponse(
        List<TimelineTweet> tweets,
        long totalItems,
        int page,
        int pageSize,
        boolean hasNext
) {}
