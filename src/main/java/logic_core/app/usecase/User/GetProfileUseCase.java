package logic_core.app.usecase.User;

import jakarta.transaction.Transactional;
import logic_core.app.cache.CacheJsonCodec;
import logic_core.app.cache.CacheKeys;
import logic_core.app.cache.CachePolicy;
import logic_core.app.cache.CacheService;
import logic_core.app.dto.request.GetProfileRequest;
import logic_core.app.dto.response.ProfileInfoResponse;
import logic_core.app.security.AuthLockOrchestrator;
import logic_core.common.exception.NotFoundException;
import logic_core.common.result.Result;
import logic_core.domain.model.UserModel;
import logic_core.domain.repository.RelationshipRepository;
import logic_core.domain.repository.TweetRepository;
import logic_core.domain.repository.UserRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GetProfileUseCase
{
    private static final Logger log = LoggerFactory.getLogger(GetProfileUseCase.class);

    @NonNull private final UserRepository repository;
    @NonNull private final AuthLockOrchestrator lockOrchestrator;
    @NonNull private final RelationshipRepository followRepository;
    @NonNull private final TweetRepository tweetRepository;
    @NonNull private final CacheService cacheService;
    @NonNull private final CacheJsonCodec cacheJsonCodec;

    @Transactional
    public Result<ProfileInfoResponse> execute(GetProfileRequest request)
    {
        try
        {
            lockOrchestrator.lockAndGetContextByToken(request.sessionToken());

            // Cache is viewer-independent (public profile data); the key is the
            // profile owner id. Only successful loads are cached; failures stay
            // uncached so they never mask later success. (V2.1 #19)
            String cacheKey = CacheKeys.userProfile(request.userId());

            String cached = cacheService.get(cacheKey);
            if (cached != null)
            {
                ProfileInfoResponse cachedResponse = safeFromJson(cached, cacheKey);
                if (cachedResponse != null)
                {
                    return Result.success(cachedResponse);
                }
            }

            UserModel user = repository.findById(request.userId())
                    .orElseThrow(()-> new NotFoundException("user not found"));


            long followers = followRepository.countFollowers(user.getId());
            long following = followRepository.countFollowing(user.getId());
            long tweets = tweetRepository.countTweetsById(user.getId());


            ProfileInfoResponse response = new ProfileInfoResponse(
                    user.getId(),
                    user.getUsername(),
                    user.getDisplayName(),
                    user.getBio(),
                    user.getAvatarUrl(),
                    user.getBannerUrl(),
                    followers,
                    following,
                    tweets,
                    user.isVerified(),
                    user.getCreatedAt());

            cacheService.put(cacheKey, cacheJsonCodec.toJson(response), CachePolicy.PROFILE);

            return Result.success(response);
        }
        catch (Exception e)
        {
            return Result.failure(e.getMessage());
        }
    }

    /**
     * Returns null (treating the entry as a miss) when a cached value cannot
     * be decoded, so corrupt cache data can never turn a healthy database read
     * into a failure response.
     */
    private ProfileInfoResponse safeFromJson(String json, String cacheKey)
    {
        try
        {
            return cacheJsonCodec.fromJson(json, ProfileInfoResponse.class);
        }
        catch (RuntimeException e)
        {
            log.warn("Discarding unreadable cached profile for key {}: {}", cacheKey, e.toString());
            return null;
        }
    }
}