package logic_core.app.dto.response;

import lombok.Builder;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Builder
public record PollResponse(
        UUID pollId,
        String question,
        OffsetDateTime expiresAt,
        boolean expired,
        long totalVotes,
        List<PollOptionResponse> options
) {}