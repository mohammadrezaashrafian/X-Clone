package logic_core.app.dto.request;

import java.util.UUID;

public record UnbookmarkTweetRequest(
        UUID tweetId,
        String sessionToken
) {}