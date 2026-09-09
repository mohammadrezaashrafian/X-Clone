package logic_core.app.dto.response;

public record HashtagFollowResponse(
        boolean following,
        long followersCount
) {}