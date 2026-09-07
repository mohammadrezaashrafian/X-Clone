package logic_core.app.dto.response;

import lombok.Builder;

import java.util.UUID;

@Builder
public record PollOptionResponse(
        UUID optionId,
        String text,
        long voteCount
) {}