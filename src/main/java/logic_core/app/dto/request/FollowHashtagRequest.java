package logic_core.app.dto.request;

public record FollowHashtagRequest(
        String tag,
        String sessionToken
) {}