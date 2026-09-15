package logic_core.app.dto.response;

import lombok.Builder;

import java.time.OffsetDateTime;
import java.util.UUID;

@Builder
public record AuthResponse(
        UUID userId,
        String username,
        String token,
        OffsetDateTime expiresAt,
        String message,
        UUID sessionId,
        boolean emailVerified
) {}
