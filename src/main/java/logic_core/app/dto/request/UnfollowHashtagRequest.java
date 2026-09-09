package logic_core.app.dto.request;

public record UnfollowHashtagRequest(
        String tag,
        String sessionToken
) {}