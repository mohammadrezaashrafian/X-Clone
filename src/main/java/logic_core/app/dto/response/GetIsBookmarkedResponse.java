package logic_core.app.dto.response;

import lombok.Builder;

@Builder
public record GetIsBookmarkedResponse(
        boolean bookmarked
) {}