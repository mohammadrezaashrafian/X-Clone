package logic_core.app.usecase.relation;

import jakarta.transaction.Transactional;
import logic_core.app.dto.request.FollowUserRequest;
import logic_core.app.dto.response.FollowResponse;
import logic_core.app.dto.validator.FollowValidator;
import logic_core.app.mapper.FollowMapper;
import logic_core.app.security.AuthLockOrchestrator;
import logic_core.app.security.SessionUserContext;
import logic_core.app.service.NotificationApplicationService;
import logic_core.common.exception.*;
import logic_core.common.result.Result;
import logic_core.common.util.TimeProvider;
import logic_core.domain.model.FollowRelation;
import logic_core.domain.model.notification.NotificationType;
import logic_core.domain.policy.FollowPolicy;
import logic_core.domain.repository.RelationshipRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FollowUserUseCase
{
    @NonNull private final FollowValidator validator;
    @NonNull private final FollowPolicy policy;
    @NonNull private final RelationshipRepository relationshipRepository;
    @NonNull private final TimeProvider timeProvider;
    @NonNull private final AuthLockOrchestrator lockOrchestrator;
    @NonNull private final NotificationApplicationService notificationService;

    @Transactional
    public Result<FollowResponse> execute(FollowUserRequest request)
    {
        if (request == null || request.followingId() == null)
        {
            return Result.failure("Following ID is required.");
        }

        try
        {
            SessionUserContext context =
                    lockOrchestrator.lockAndGetContextByToken(
                            request.sessionToken()
                    );

            UUID followerId = context.lockedUser().getId();
            UUID followingId = request.followingId();

            validator.validate(followerId, followingId);
            policy.validateFollow(followerId, followingId);

            FollowRelation followRelation = FollowRelation.create(
                    followerId,
                    followingId,
                    timeProvider.now()
            );
            relationshipRepository.saveFollow(followRelation);

            notificationService.notify(
                    followingId,
                    followerId,
                    null,
                    NotificationType.FOLLOW
            );

            long followersCount = relationshipRepository.countFollowers(followingId);


            return Result.success(FollowMapper.toResponse(true, followersCount));
        }
        catch (AppException e)
        {
            return Result.failure(e.getMessage());
        }
        catch (Exception e)
        {
            return Result.failure("Failed to follow user due to a concurrency or database error.");
        }
    }
}
