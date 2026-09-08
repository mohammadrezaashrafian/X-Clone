package logic_core.app.usecase.notification;

import jakarta.transaction.Transactional;
import logic_core.app.dto.request.GetNotificationsRequest;
import logic_core.app.dto.response.GetNotificationsPageResponse;
import logic_core.app.dto.response.NotificationResponse;
import logic_core.app.dto.response.UserSummaryResponse;
import logic_core.app.mapper.NotificationResponseMapper;
import logic_core.app.mapper.UserSummaryResponseMapper;
import logic_core.app.security.AuthLockOrchestrator;
import logic_core.app.security.SessionUserContext;
import logic_core.common.exception.AppException;
import logic_core.common.result.Result;
import logic_core.domain.model.NotificationModel;
import logic_core.domain.model.UserModel;
import logic_core.domain.repository.NotificationRepository;
import logic_core.domain.repository.UserRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Returns the authenticated user's notifications, newest first. The recipient
 * is derived from the session — never from a caller-supplied field.
 */
@Service
@RequiredArgsConstructor
public class GetNotificationsUseCase
{
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    @NonNull private final NotificationRepository notificationRepository;
    @NonNull private final UserRepository userRepository;
    @NonNull private final AuthLockOrchestrator lockOrchestrator;

    @Transactional
    public Result<GetNotificationsPageResponse> execute(
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

            int page = request.page() != null && request.page() >= 0
                    ? request.page() : 0;
            int pageSize = request.pageSize() != null && request.pageSize() > 0
                    ? Math.min(request.pageSize(), MAX_PAGE_SIZE)
                    : DEFAULT_PAGE_SIZE;

            List<NotificationModel> notifications =
                    notificationRepository.findPageByRecipientId(
                            userId, page, pageSize);

            long totalItems = notificationRepository.countByRecipientId(userId);

            boolean hasNext = (long) (page + 1) * pageSize < totalItems;

            return Result.success(new GetNotificationsPageResponse(
                    toResponses(notifications),
                    totalItems,
                    page,
                    pageSize,
                    hasNext
            ));
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

    /**
     * Maps notifications to responses, resolving all actors in one batched
     * query (no N+1). Notifications whose actor no longer exists keep a null
     * actor in the response.
     */
    private List<NotificationResponse> toResponses(
            List<NotificationModel> notifications)
    {
        if (notifications.isEmpty())
        {
            return List.of();
        }

        List<UUID> actorIds = notifications.stream()
                .map(NotificationModel::getActorId)
                .filter(id -> id != null)
                .distinct()
                .toList();

        Map<UUID, UserSummaryResponse> actorsById =
                userRepository.findByIds(actorIds).stream()
                        .collect(Collectors.toMap(
                                UserModel::getId,
                                UserSummaryResponseMapper::toResponse,
                                (first, second) -> first));

        return notifications.stream()
                .map(model -> NotificationResponseMapper.toResponse(
                        model,
                        model.getActorId() != null
                                ? actorsById.get(model.getActorId())
                                : null))
                .toList();
    }
}