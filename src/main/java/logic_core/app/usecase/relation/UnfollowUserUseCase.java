package logic_core.app.usecase.relation;

import jakarta.transaction.Transactional;
import logic_core.app.dto.request.UnfollowUserRequest;
import logic_core.app.dto.response.FollowResponse;
import logic_core.app.dto.validator.FollowValidator;
import logic_core.app.mapper.FollowMapper;
import logic_core.app.service.NotificationApplicationService;
import logic_core.app.security.AuthLockOrchestrator;
import logic_core.app.security.SessionUserContext;
import logic_core.common.exception.AppException;
import logic_core.common.result.Result;
import logic_core.common.util.TimeProvider;
import logic_core.domain.model.notification.NotificationType;
import logic_core.domain.policy.FollowPolicy;
import logic_core.domain.repository.RelationshipRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UnfollowUserUseCase
{
    @NonNull private final FollowValidator validator;
    @NonNull private final FollowPolicy policy;
    @NonNull private final RelationshipRepository relationshipRepository;
    @NonNull private final NotificationApplicationService notificationService;
    @NonNull private final TimeProvider timeProvider;
    @NonNull private final AuthLockOrchestrator lockOrchestrator;

    @Transactional
    public Result<FollowResponse> execute(UnfollowUserRequest request)
    {
        if (request == null || request.unfollowedId() == null) {
            return Result.failure("Unfollowed ID is required.");
        }

        try
        {
            SessionUserContext context =
                    lockOrchestrator.lockAndGetContextByToken(
                            request.sessionToken()
                    );

            UUID unfollowerId = context.lockedUser().getId();;
            UUID unfollowedId = request.unfollowedId();

            validator.validate(unfollowerId, unfollowedId);
            policy.validateUnfollow(unfollowerId, unfollowedId);

            relationshipRepository.findFollowRelation(unfollowerId, unfollowedId)
                    .ifPresent(relationshipRepository::deleteFollow);

            // V2.1 #7: undoing the follow also removes the FOLLOW notification
            // it generated, so the recipient is not left with a ghost.
            notificationService.retractInteraction(
                    unfollowedId,
                    unfollowerId,
                    NotificationType.FOLLOW,
                    null
            );

            long followersCount = relationshipRepository.countFollowers(unfollowedId);


            return Result.success(FollowMapper.toResponse(false, followersCount));
        }
        catch (AppException e)
        {
            return Result.failure(e.getMessage());
        }
        catch (Exception e)
        {
            return Result.failure("Failed to unfollow user due to a system error.");
        }
    }
}
