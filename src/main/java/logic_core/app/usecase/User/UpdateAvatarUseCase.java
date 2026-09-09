package logic_core.app.usecase.User;


import logic_core.app.cache.CacheInvalidation;
import logic_core.app.cache.CacheKeys;
import logic_core.app.dto.request.UpdateAvatarRequest;
import logic_core.app.dto.response.UpdateAvatarResponse;
import logic_core.app.dto.validator.UserValidator;
import logic_core.app.security.AuthLockOrchestrator;
import logic_core.app.security.SessionUserContext;
import logic_core.common.result.Result;
import logic_core.domain.model.UserModel;
import logic_core.domain.policy.UserPolicy;
import logic_core.domain.repository.UserRepository;
import logic_core.domain.service.MediaStorageService;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
public class UpdateAvatarUseCase
{
    @NonNull private final AuthLockOrchestrator authLockOrchestrator;
    @NonNull private final UserRepository userRepository;
    @NonNull private final UserValidator userValidator;
    @NonNull private final UserPolicy userPolicy;
    @NonNull private final MediaStorageService mediaStorageService;
    @NonNull private final CacheInvalidation cacheInvalidation;

    public Result<UpdateAvatarResponse> execute(UpdateAvatarRequest request)
    {
        try
        {
            userValidator.validateAvatar(request.avatar());

            SessionUserContext context = authLockOrchestrator.lockAndGetContextByToken(request.sessionToken());

            UserModel user = context.lockedUser();

            userPolicy.validateCanUpdateProfile(user);

            String oldAvatar = user.getAvatarUrl();

            String newAvatarUrl = mediaStorageService.uploadAvatar(request.avatar());

            user.setAvatarUrl(newAvatarUrl);
            user.setUpdatedAt(OffsetDateTime.now());

            userRepository.update(user);

            if(oldAvatar != null && !oldAvatar.isBlank())
            {
                mediaStorageService.delete(oldAvatar);
            }

            cacheInvalidation.evictAfterCommit(CacheKeys.userProfile(user.getId()));

            return Result.success(new UpdateAvatarResponse(user.getId(), newAvatarUrl));

        }
        catch(Exception e)
        {
            return Result.failure(e.getMessage());
        }
    }
}