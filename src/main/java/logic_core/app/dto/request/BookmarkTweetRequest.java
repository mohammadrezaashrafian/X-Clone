package logic_core.app.dto.request;

import java.util.UUID;

public record BookmarkTweetRequest(
        UUID tweetId,
        String sessionToken
) {}