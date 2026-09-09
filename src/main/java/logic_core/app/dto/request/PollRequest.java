package logic_core.app.dto.request;

import java.util.List;

public record PollRequest(
        String question,
        List<String> options,
        int durationMinutes
) {}