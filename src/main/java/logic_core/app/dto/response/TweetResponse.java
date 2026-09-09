package logic_core.app.dto.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record TweetResponse(
        UUID id,
        String content,
        UserSummaryResponse author,
        boolean isDeleted,
        boolean isPinned,
        OffsetDateTime createdAt,
        OffsetDateTime publishedAt,
        UUID repliedToId,
        TweetResponse retweetedTweet,
        TweetResponse quotedTweet,
        long likeCount,
        long replyCount,
        long retweetCount,
        List<MediaResponse> media,
        PollResponse poll
) {}