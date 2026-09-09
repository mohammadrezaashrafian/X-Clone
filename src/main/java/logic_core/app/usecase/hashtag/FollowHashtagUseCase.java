package logic_core.app.usecase.hashtag;

import jakarta.transaction.Transactional;
import logic_core.app.dto.request.FollowHashtagRequest;
import logic_core.app.dto.response.HashtagFollowResponse;
import logic_core.app.mapper.HashtagMapper;
import logic_core.app.security.AuthLockOrchestrator;
import logic_core.app.security.SessionUserContext;
import logic_core.common.exception.AppException;
import logic_core.common.exception.ConflictException;
import logic_core.common.result.Result;
import logic_core.common.util.TimeProvider;
import logic_core.domain.model.HashtagFollow;
import logic_core.domain.model.HashtagModel;
import logic_core.domain.repository.HashtagRepository;
import logic_core.domain.service.HashtagExtractor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FollowHashtagUseCase
{
    @NonNull private final HashtagRepository hashtagRepository;
    @NonNull private final TimeProvider timeProvider;
    @NonNull private final AuthLockOrchestrator lockOrchestrator;

    @Transactional
    public Result<HashtagFollowResponse> execute(FollowHashtagRequest request)
    {
        if (request == null || request.tag() == null || request.tag().isBlank())
        {
            return Result.failure("Hashtag is required.");
        }

        try
        {
            SessionUserContext context =
                    lockOrchestrator.lockAndGetContextByToken(
                            request.sessionToken()
                    );

            UUID userId = context.lockedUser().getId();

            String tag = HashtagExtractor.normalize(request.tag());

            HashtagModel hashtag = hashtagRepository.findByTag(tag)
                    .orElseGet(() -> hashtagRepository.save(
                            HashtagModel.create(tag, timeProvider.now())
                    ));

            if (hashtagRepository.isFollowing(userId, hashtag.getId()))
            {
                throw new ConflictException("Already following this hashtag.");
            }

            hashtagRepository.saveFollow(
                    HashtagFollow.create(
                            userId,
                            hashtag.getId(),
                            timeProvider.now()
                    )
            );

            long followersCount =
                    hashtagRepository.countFollowers(hashtag.getId());

            return Result.success(
                    HashtagMapper.toResponse(true, followersCount)
            );
        }
        catch (AppException e)
        {
            return Result.failure(e.getMessage());
        }
        catch (Exception e)
        {
            return Result.failure(
                    "Failed to follow hashtag due to a system error."
            );
        }
    }
}