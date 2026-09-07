package logic_core.app.usecase.notification;

import jakarta.transaction.Transactional;
import logic_core.app.dto.request.ReadAllNotificationsRequest;
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
 * Marks every unread notification of the authenticated user as read and
 * returns the number of notifications that were actually updated.
 */
@Service
@RequiredArgsConstructor
public class ReadAllNotificationsUseCase
{
    @NonNull private final NotificationRepository notificationRepository;
    @NonNull private final AuthLockOrchestrator lockOrchestrator;

    @Transactional
    public Result<Integer> execute(ReadAllNotificationsRequest request)
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

            int updated = notificationRepository.markAllAsRead(userId);

            return Result.success(updated);
        }
        catch (AppException e)
        {
            return Result.failure(e.getMessage());
        }
        catch (Exception e)
        {
            return Result.failure("Failed to mark notifications as read.");
        }
    }
}