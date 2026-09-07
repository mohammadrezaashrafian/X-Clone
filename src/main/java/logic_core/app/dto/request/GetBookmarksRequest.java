package logic_core.app.dto.request;

public record GetBookmarksRequest(
        int page,
        int pageSize,
        String sessionToken
) {}