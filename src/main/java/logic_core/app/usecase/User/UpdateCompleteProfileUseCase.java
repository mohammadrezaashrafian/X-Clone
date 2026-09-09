package logic_core.app.usecase.User;

import logic_core.app.cache.CacheInvalidation;
import logic_core.app.cache.CacheKeys;
import logic_core.app.dto.request.UpdateCompleteProfileRequest;
import logic_core.app.dto.response.ProfileInfoResponse;
import logic_core.app.dto.response.UpdateCompleteProfileResponse;
import logic_core.app.dto.validator.UserValidator;
import logic_core.app.security.AuthLockOrchestrator;
import logic_core.app.security.SessionUserContext;
import logic_core.common.result.Result;
import logic_core.domain.model.UserModel;
import logic_core.domain.policy.UserPolicy;
import logic_core.domain.repository.RelationshipRepository;
import logic_core.domain.repository.TweetRepository;
import logic_core.domain.repository.UserRepository;
import logic_core.domain.service.MediaStorageService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UpdateCompleteProfileUseCase
{
    @NonNull private final AuthLockOrchestrator authLockOrchestrator;
    @NonNull private final UserRepository userRepository;
    @NonNull private final RelationshipRepository relationshipRepository;
    @NonNull private final TweetRepository tweetRepository;
    @NonNull private final MediaStorageService mediaStorageService;
    @NonNull private final UserValidator userValidator;
    @NonNull private final UserPolicy userPolicy;
    @NonNull private final CacheInvalidation cacheInvalidation;

    public Result<UpdateCompleteProfileResponse> execute(UpdateCompleteProfileRequest request)
    {
        try
        {
            userValidator.validateAllProfile(request);

            SessionUserContext context = authLockOrchestrator.lockAndGetContextByToken(request.sessionToken());

            UserModel user = context.lockedUser();

            userPolicy.validateCanUpdateProfile(user);

            Optional<UserModel> existing = userRepository.findByUsername(request.username());

            if (existing.isPresent() && !existing.get().getId().equals(user.getId()))
            {
                return Result.failure("USERNAME_ALREADY_EXISTS");
            }

            if (request.avatar() != null)
            {
                if (user.getAvatarUrl() != null &&
                        !user.getAvatarUrl().isBlank())
                {
                    mediaStorageService.delete(user.getAvatarUrl());
                }

                String avatarPath = mediaStorageService.uploadAvatar(request.avatar());

                user.setAvatarUrl(avatarPath);
            }

            if (request.banner() != null)
            {
                if (user.getBannerUrl() != null &&
                        !user.getBannerUrl().isBlank())
                {
                    mediaStorageService.delete(user.getBannerUrl());
                }

                String bannerPath = mediaStorageService.uploadBanner(request.banner());

                user.setBannerUrl(bannerPath);
            }

            user.setDisplayName(request.displayName());
            user.setUsername(request.username());
            user.setBio(request.bio());

            userRepository.update(user);

            ProfileInfoResponse profile = new ProfileInfoResponse(
                    user.getId(),
                    user.getUsername(),
                    user.getDisplayName(),
                    user.getBio(),
                    user.getAvatarUrl(),
                    user.getBannerUrl(),
                    relationshipRepository.countFollowers(user.getId()),
                    relationshipRepository.countFollowing(user.getId()),
                    tweetRepository.countTweetsById(user.getId()),
                    user.isVerified(),
                    OffsetDateTime.now()
            );

            cacheInvalidation.evictAfterCommit(CacheKeys.userProfile(user.getId()));

            return Result.success(new UpdateCompleteProfileResponse(profile)
            );
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return Result.failure(e.getMessage());
        }
    }
}