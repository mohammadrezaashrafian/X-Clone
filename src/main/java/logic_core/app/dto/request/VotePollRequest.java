package logic_core.app.dto.request;

import java.util.UUID;

public record VotePollRequest(
        UUID pollId,
        UUID optionId,
        String sessionToken
) {}