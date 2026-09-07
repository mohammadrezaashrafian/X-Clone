package logic_core.app.dto.request;

import java.util.UUID;

public record ReadNotificationRequest(
        UUID notificationId,
        String sessionToken
) {}