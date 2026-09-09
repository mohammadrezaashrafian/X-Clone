package logic_core.app.usecase.User;


import logic_core.app.cache.CacheInvalidation;
import logic_core.app.cache.CacheKeys;
import logic_core.app.dto.request.UpdateBioRequest;
import logic_core.app.dto.response.UpdateBioResponse;
import logic_core.app.dto.validator.UserValidator;
import logic_core.app.security.AuthLockOrchestrator;
import logic_core.app.security.SessionUserContext;
import logic_core.common.result.Result;
import logic_core.domain.model.UserModel;
import logic_core.domain.policy.UserPolicy;
import logic_core.domain.repository.UserRepository;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
public class UpdateBioUseCase
{
    @NonNull private final AuthLockOrchestrator authLockOrchestrator;
    @NonNull  private final UserRepository userRepository;
    @NonNull  private final UserValidator userValidator;
    @NonNull private final UserPolicy userPolicy;
    @NonNull private final CacheInvalidation cacheInvalidation;

    public Result<UpdateBioResponse> execute(UpdateBioRequest request)
    {
        try
        {
           userValidator.validateBio(request.bio());
            SessionUserContext context = authLockOrchestrator.lockAndGetContextByToken(request.sessionToken());

            UserModel user = context.lockedUser();

            userPolicy.validateCanUpdateProfile(user);
            user.setBio(request.bio());
            user.setUpdatedAt(OffsetDateTime.now());

            userRepository.update(user);

            cacheInvalidation.evictAfterCommit(CacheKeys.userProfile(user.getId()));

            return Result.success(new UpdateBioResponse(
                    user.getId(),
                    user.getBio())
            );

        }
        catch(Exception e)
        {
            return Result.failure(e.getMessage());
        }
    }
}