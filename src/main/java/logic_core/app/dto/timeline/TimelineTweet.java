package logic_core.app.dto.timeline;

import logic_core.app.dto.response.MediaResponse;
import logic_core.app.dto.response.PollResponse;
import lombok.Builder;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Builder
public record TimelineTweet(
        UUID tweetId,
        UUID authorId,
        String username,
        String displayName,
        String avatarUrl,
        String content,
        long likeCount,
        long replyCount,
        long retweetCount,
        boolean isLiked,
        OffsetDateTime publishedAt,
        List<TimelineMedia> media,
        PollResponse poll
) {}