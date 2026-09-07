package logic_core.app.facade;

import logic_core.app.dto.request.GetNotificationsRequest;
import logic_core.app.dto.request.ReadAllNotificationsRequest;
import logic_core.app.dto.request.ReadNotificationRequest;
import logic_core.app.dto.response.NotificationResponse;
import logic_core.app.usecase.notification.GetNotificationsUseCase;
import logic_core.app.usecase.notification.ReadAllNotificationsUseCase;
import logic_core.app.usecase.notification.ReadNotificationUseCase;
import logic_core.common.result.Result;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class NotificationFacade
{
    @NonNull private final GetNotificationsUseCase getNotificationsUseCase;
    @NonNull private final ReadNotificationUseCase readNotificationUseCase;
    @NonNull private final ReadAllNotificationsUseCase readAllNotificationsUseCase;

    public Result<List<NotificationResponse>> getNotifications(
            GetNotificationsRequest request)
    {
        return getNotificationsUseCase.execute(request);
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