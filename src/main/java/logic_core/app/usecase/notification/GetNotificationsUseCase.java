package logic_core.app.usecase.notification;

import jakarta.transaction.Transactional;
import logic_core.app.dto.request.GetNotificationsRequest;
import logic_core.app.dto.response.NotificationResponse;
import logic_core.app.dto.response.UserSummaryResponse;
import logic_core.app.mapper.NotificationResponseMapper;
import logic_core.app.mapper.UserSummaryResponseMapper;
import logic_core.app.security.AuthLockOrchestrator;
import logic_core.app.security.SessionUserContext;
import logic_core.common.exception.AppException;
import logic_core.common.result.Result;
import logic_core.domain.model.NotificationModel;
import logic_core.domain.repository.NotificationRepository;
import logic_core.domain.repository.UserRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Returns the authenticated user's notifications, newest first. The recipient
 * is derived from the session — never from a caller-supplied field.
 */
@Service
@RequiredArgsConstructor
public class GetNotificationsUseCase
{
    @NonNull private final NotificationRepository notificationRepository;
    @NonNull private final UserRepository userRepository;
    @NonNull private final AuthLockOrchestrator lockOrchestrator;

    @Transactional
    public Result<List<NotificationResponse>> execute(
            GetNotificationsRequest request)
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

            List<NotificationResponse> notifications =
                    notificationRepository.findByRecipientId(userId)
                            .stream()
                            .map(this::toResponse)
                            .toList();

            return Result.success(notifications);
        }
        catch (AppException e)
        {
            return Result.failure(e.getMessage());
        }
        catch (Exception e)
        {
            return Result.failure("Failed to load notifications.");
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