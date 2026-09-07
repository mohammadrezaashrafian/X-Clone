package logic_core.app.usecase.hashtag;

import logic_core.app.dto.request.GetHashtagTweetsRequest;
import logic_core.app.dto.response.HashtagTweetsResponse;
import logic_core.app.dto.timeline.TimelineTweet;
import logic_core.app.security.AuthLockOrchestrator;
import logic_core.app.security.SessionUserContext;
import logic_core.common.exception.ForbiddenException;
import logic_core.common.exception.NotFoundException;
import logic_core.common.exception.ValidationException;
import logic_core.common.result.Result;
import logic_core.domain.model.HashtagModel;
import logic_core.domain.repository.HashtagRepository;
import logic_core.domain.service.HashtagExtractor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GetHashtagTweetsUseCase
{
    private static final int MAX_PAGE_SIZE = 100;

    @NonNull private final HashtagRepository hashtagRepository;
    @NonNull private final AuthLockOrchestrator lockOrchestrator;

    public Result<HashtagTweetsResponse> execute(GetHashtagTweetsRequest request)
    {
        try
        {
            SessionUserContext context =
                    lockOrchestrator.lockAndGetContextByToken(
                            request.sessionToken()
                    );

            UUID actorId = context.lockedUser().getId();

            if (request == null
                    || request.tag() == null
                    || request.tag().isBlank())
            {
                throw new ValidationException("hashtag.tag.required");
            }

            String tag = HashtagExtractor.normalize(request.tag());

            int page = Math.max(request.page(), 0);
            int pageSize = clampPageSize(request.pageSize());

            HashtagModel hashtag = hashtagRepository.findByTag(tag)
                    .orElse(null);

            if (hashtag == null)
            {
                return Result.success(emptyResponse(tag, page, pageSize));
            }

            int offset = page * pageSize;

            List<TimelineTweet> tweets =
                    hashtagRepository.getTweetsByHashtag(
                            actorId,
                            hashtag.getId(),
                            pageSize,
                            offset
                    );

            long totalItems =
                    hashtagRepository.countTweetsByHashtag(
                            actorId,
                            hashtag.getId()
                    );

            boolean hasNext = offset + tweets.size() < totalItems;

            return Result.success(
                    HashtagTweetsResponse.builder()
                            .tag(tag)
                            .tweets(tweets)
                            .totalItems(totalItems)
                            .page(page)
                            .pageSize(pageSize)
                            .hasNext(hasNext)
                            .build()
            );
        }
        catch (ValidationException | ForbiddenException | NotFoundException e)
        {
            return Result.failure(e.getMessage());
        }
    }

    private static int clampPageSize(int pageSize)
    {
        if (pageSize <= 0)
        {
            return 20;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private static HashtagTweetsResponse emptyResponse(
            String tag,
            int page,
            int pageSize)
    {
        return HashtagTweetsResponse.builder()
                .tag(tag)
                .tweets(List.of())
                .totalItems(0)
                .page(page)
                .pageSize(pageSize)
                .hasNext(false)
                .build();
    }
}