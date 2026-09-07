package logic_core.app.dto.request;

import java.util.UUID;

public record UnretweetRequest(
        UUID tweetId,
        String sessionToken
) {}