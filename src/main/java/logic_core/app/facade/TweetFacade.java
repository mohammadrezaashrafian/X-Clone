package logic_core.app.facade;

import logic_core.app.dto.request.*;
import logic_core.app.dto.response.LikeResponse;
import logic_core.app.dto.response.TweetResponse;
import logic_core.app.dto.timeline.TimelineTweet;
import logic_core.app.usecase.follow.GetRepliesUseCase;
import logic_core.app.usecase.tweet.*;

import java.util.List;
import logic_core.common.result.Result;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TweetFacade
{
    private final CreateTweetUseCase createTweetUseCase;
    private final DeleteTweetUseCase deleteTweetUseCase;
    private final EditTweetUseCase editTweetUseCase;
    private final GetTweetUseCase getTweetUseCase;
    private final LikeTweetUseCase likeTweetUseCase;
    private final ReplyTweetUseCase replyTweetUseCase;
    private final RetweetUseCase retweetUseCase;
    private final UnretweetUseCase unretweetUseCase;
    private final UnlikeTweetUseCase unlikeTweetUseCase;
    private final GetRepliesUseCase getRepliesUseCase;

    public Result<TweetResponse> createTweet(CreateTweetRequest request)
    {
        return createTweetUseCase.execute(request);
    }

    public Result<TweetResponse> deleteTweet(DeleteTweetRequest request)
    {
        return deleteTweetUseCase.execute(request);
    }

    public Result<TweetResponse> editTweet(EditTweetRequest request)
    {
        return editTweetUseCase.execute(request);
    }

    public Result<TimelineTweet> getTweet(GetTweetRequest request)
    {
        return getTweetUseCase.execute(request);
    }

    public Result<LikeResponse> likeTweet(LikeTweetRequest request)
    {
        return likeTweetUseCase.execute(request);
    }

    public Result<TweetResponse> replyTweet(ReplyTweetRequest request)
    {
        return replyTweetUseCase.execute(request);
    }

    public Result<TweetResponse> retweet(RetweetRequest request)
    {
        return retweetUseCase.execute(request);
    }

    public Result<TweetResponse> unretweet(UnretweetRequest request)
    {
        return unretweetUseCase.execute(request);
    }

    public Result<LikeResponse> unlikeTweet(UnlikeTweetRequest request)
    {
        return unlikeTweetUseCase.execute(request);
    }

    public Result<List<TimelineTweet>> getReplies(GetRepliesRequest request)
    {
        return getRepliesUseCase.execute(request);
    }
}
