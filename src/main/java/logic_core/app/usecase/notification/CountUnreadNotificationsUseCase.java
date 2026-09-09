package logic_core.app.usecase.notification;

import jakarta.transaction.Transactional;
import logic_core.app.dto.request.CountUnreadNotificationsRequest;
import logic_core.app.dto.response.UnreadNotificationsCountResponse;
import logic_core.app.security.AuthLockOrchestrator;
import logic_core.app.security.SessionUserContext;
import logic_core.common.exception.AppException;
import logic_core.common.result.Result;
import logic_core.domain.repository.NotificationRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Returns the authenticated user's unread notification count (V2.1 #7). The
 * recipient is derived from the session — never from a caller-supplied field.
 */
@Service
@RequiredArgsConstructor
public class CountUnreadNotificationsUseCase
{
    @NonNull private final NotificationRepository notificationRepository;
    @NonNull private final AuthLockOrchestrator lockOrchestrator;

    @Transactional
    public Result<UnreadNotificationsCountResponse> execute(
            CountUnreadNotificationsRequest request)
    {
        if (request == null)
        {
            return Result.failure("Request cannot be null.");
        }

        try
        {
            SessionUserContext context =
                    lockOrchestrator.lockAndGetContextByToken(
                            request.sessionToken()
                    );

            UUID userId = context.lockedUser().getId();

            long unreadCount =
                    notificationRepository.countUnreadByRecipientId(userId);

            return Result.success(
                    new UnreadNotificationsCountResponse(unreadCount));
        }
        catch (AppException e)
        {
            return Result.failure(e.getMessage());
        }
        catch (Exception e)
        {
            return Result.failure("Failed to count unread notifications.");
        }
    }
}
