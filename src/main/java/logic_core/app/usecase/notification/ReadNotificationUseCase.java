package logic_core.app.usecase.notification;

import jakarta.transaction.Transactional;
import logic_core.app.dto.request.ReadNotificationRequest;
import logic_core.app.dto.response.NotificationResponse;
import logic_core.app.dto.response.UserSummaryResponse;
import logic_core.app.mapper.NotificationResponseMapper;
import logic_core.app.mapper.UserSummaryResponseMapper;
import logic_core.app.security.AuthLockOrchestrator;
import logic_core.app.security.SessionUserContext;
import logic_core.common.exception.AppException;
import logic_core.common.exception.ForbiddenException;
import logic_core.common.exception.NotFoundException;
import logic_core.common.result.Result;
import logic_core.domain.model.NotificationModel;
import logic_core.domain.repository.NotificationRepository;
import logic_core.domain.repository.UserRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Marks a single notification of the authenticated user as read. The
 * notification must belong to the authenticated user; another user's
 * notification can never be modified by supplying its ID.
 */
@Service
@RequiredArgsConstructor
public class ReadNotificationUseCase
{
    @NonNull private final NotificationRepository notificationRepository;
    @NonNull private final UserRepository userRepository;
    @NonNull private final AuthLockOrchestrator lockOrchestrator;

    @Transactional
    public Result<NotificationResponse> execute(
            ReadNotificationRequest request)
    {
        if (request == null || request.notificationId() == null)
        {
            return Result.failure("Notification ID is required.");
        }

        try
        {
            SessionUserContext context =
                    lockOrchestrator.lockAndGetContextByToken(
                            request.sessionToken()
                    );

            UUID userId = context.lockedUser().getId();

            NotificationModel notification =
                    notificationRepository.findById(request.notificationId())
                            .orElseThrow(() ->
                                    new NotFoundException(
                                            "Notification not found."
                                    )
                            );

            if (!userId.equals(notification.getRecipientId()))
            {
                throw new ForbiddenException(
                        "You cannot modify this notification."
                );
            }

            notification.markAsRead();
            notificationRepository.markAsRead(notification.getId());

            return Result.success(toResponse(notification));
        }
        catch (AppException e)
        {
            return Result.failure(e.getMessage());
        }
        catch (Exception e)
        {
            return Result.failure("Failed to mark notification as read.");
        }
    }

    private NotificationResponse toResponse(NotificationModel model)
    {
        UserSummaryResponse actor =
                model.getActorId() != null
                        ? userRepository.findById(model.getActorId())
                                .map(UserSummaryResponseMapper::toResponse)
                                .orElse(null)
                        : null;

        return NotificationResponseMapper.toResponse(model, actor);
    }
}