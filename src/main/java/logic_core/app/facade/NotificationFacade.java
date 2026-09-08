package logic_core.app.facade;

import logic_core.app.dto.request.CountUnreadNotificationsRequest;
import logic_core.app.dto.request.GetNotificationsRequest;
import logic_core.app.dto.request.ReadAllNotificationsRequest;
import logic_core.app.dto.request.ReadNotificationRequest;
import logic_core.app.dto.response.GetNotificationsPageResponse;
import logic_core.app.dto.response.NotificationResponse;
import logic_core.app.dto.response.UnreadNotificationsCountResponse;
import logic_core.app.usecase.notification.CountUnreadNotificationsUseCase;
import logic_core.app.usecase.notification.GetNotificationsUseCase;
import logic_core.app.usecase.notification.ReadAllNotificationsUseCase;
import logic_core.app.usecase.notification.ReadNotificationUseCase;
import logic_core.common.result.Result;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class NotificationFacade
{
    @NonNull private final GetNotificationsUseCase getNotificationsUseCase;
    @NonNull private final ReadNotificationUseCase readNotificationUseCase;
    @NonNull private final ReadAllNotificationsUseCase readAllNotificationsUseCase;
    @NonNull private final CountUnreadNotificationsUseCase countUnreadNotificationsUseCase;

    public Result<GetNotificationsPageResponse> getNotifications(
            GetNotificationsRequest request)
    {
        return getNotificationsUseCase.execute(request);
    }

    public Result<UnreadNotificationsCountResponse> countUnreadNotifications(
            CountUnreadNotificationsRequest request)
    {
        return countUnreadNotificationsUseCase.execute(request);
    }

    public Result<NotificationResponse> readNotification(
            ReadNotificationRequest request)
    {
        return readNotificationUseCase.execute(request);
    }

    public Result<Integer> readAllNotifications(
            ReadAllNotificationsRequest request)
    {
        return readAllNotificationsUseCase.execute(request);
    }
}