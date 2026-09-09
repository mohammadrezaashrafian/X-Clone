package logic_core.app.facade;

import logic_core.app.dto.request.FollowHashtagRequest;
import logic_core.app.dto.request.GetHashtagTweetsRequest;
import logic_core.app.dto.request.SearchHashtagsRequest;
import logic_core.app.dto.request.UnfollowHashtagRequest;
import logic_core.app.dto.response.HashtagFollowResponse;
import logic_core.app.dto.response.HashtagSearchResponse;
import logic_core.app.dto.response.HashtagTweetsResponse;
import logic_core.app.usecase.hashtag.FollowHashtagUseCase;
import logic_core.app.usecase.hashtag.GetHashtagTweetsUseCase;
import logic_core.app.usecase.hashtag.SearchHashtagsUseCase;
import logic_core.app.usecase.hashtag.UnfollowHashtagUseCase;
import logic_core.common.result.Result;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class HashtagFacade
{
    private final FollowHashtagUseCase followHashtagUseCase;
    private final UnfollowHashtagUseCase unfollowHashtagUseCase;
    private final GetHashtagTweetsUseCase getHashtagTweetsUseCase;
    private final SearchHashtagsUseCase searchHashtagsUseCase;

    public Result<HashtagFollowResponse> follow(FollowHashtagRequest request)
    {
        return followHashtagUseCase.execute(request);
    }

    public Result<HashtagFollowResponse> unfollow(UnfollowHashtagRequest request)
    {
        return unfollowHashtagUseCase.execute(request);
    }

    public Result<HashtagTweetsResponse> getTweets(GetHashtagTweetsRequest request)
    {
        return getHashtagTweetsUseCase.execute(request);
    }

    public Result<HashtagSearchResponse> searchHashtags(SearchHashtagsRequest request)
    {
        return searchHashtagsUseCase.execute(request);
    }
}