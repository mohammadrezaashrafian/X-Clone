package logic_core.app.dto.request;

public record GetHashtagTweetsRequest(
        String tag,
        int page,
        int pageSize,
        String sessionToken
) {}