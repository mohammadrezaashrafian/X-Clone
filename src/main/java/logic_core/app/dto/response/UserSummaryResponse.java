package logic_core.app.dto.response;


import lombok.Builder;

import java.util.UUID;

@Builder
public record UserSummaryResponse(
        UUID userId,
        String username,
        String displayName,
        String avatarUrl,
        Boolean verified
) {}