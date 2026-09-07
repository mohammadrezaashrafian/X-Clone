package logic_core.app.usecase.tweet;

import org.springframework.transaction.annotation.Transactional;
import logic_core.app.dto.request.DeleteTweetRequest;
import logic_core.app.dto.response.TweetResponse;
import logic_core.app.dto.response.UserSummaryResponse;
import logic_core.app.mapper.TweetMapper;
import logic_core.app.mapper.UserSummaryResponseMapper;
import logic_core.app.security.AuthLockOrchestrator;
import logic_core.app.security.SessionUserContext;
import logic_core.common.exception.ConflictException;
import logic_core.common.exception.ForbiddenException;
import logic_core.common.exception.NotFoundException;
import logic_core.common.exception.ValidationException;
import logic_core.common.result.Result;
import logic_core.common.util.TimeProvider;
import logic_core.domain.model.TweetModel;
import logic_core.domain.model.UserModel;
import logic_core.domain.repository.BookmarkRepository;
import logic_core.domain.repository.MediaRepository;
import logic_core.domain.repository.MentionRepository;
import logic_core.domain.repository.RelationshipRepository;
import logic_core.domain.repository.TweetEditRepository;
import logic_core.domain.repository.TweetRepository;
import logic_core.domain.repository.UserRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DeleteTweetUseCase
{
    @NonNull private final TweetRepository tweetRepository;
    @NonNull private final UserRepository userRepository;
    @NonNull private final MediaRepository mediaRepository;
    @NonNull private final RelationshipRepository relationshipRepository;
    @NonNull private final TweetEditRepository tweetEditRepository;
    @NonNull private final MentionRepository mentionRepository;
    @NonNull private final BookmarkRepository bookmarkRepository;
    @NonNull private final AuthLockOrchestrator lockOrchestrator;

    @Transactional
    public Result<TweetResponse> execute(DeleteTweetRequest request)
    {
        if (request == null || request.tweetId() == null)
        {
            return Result.failure("Tweet ID is required.");
        }

        try
        {
            SessionUserContext context =
                    lockOrchestrator.lockAndGetContextByToken(
                            request.sessionToken()
                    );

            UUID currentUserId = context.lockedUser().getId();

            TweetModel tweet =
                    tweetRepository.findActiveById(request.tweetId())
                            .orElseThrow(() ->
                                    new RuntimeException("Tweet not found or already deleted.")
                            );

            if (!tweet.getAuthorId().equals(currentUserId))
            {
                return Result.failure("You are not authorized to delete this tweet.");
            }

            tweetRepository.softDelete(tweet.getId());

            // Cascade: hard-delete related entities for migrated features
            mediaRepository.deleteByTweetId(tweet.getId());
            relationshipRepository.deleteLikesByTweetId(tweet.getId());
            tweetEditRepository.deleteByTweetId(tweet.getId());
            mentionRepository.deleteByTweetId(tweet.getId());
            bookmarkRepository.deleteByTweetId(tweet.getId());

            TweetModel deletedTweet =
                    tweetRepository.findById(tweet.getId())
                            .orElseThrow(() ->
                                    new RuntimeException("Failed to reload deleted tweet.")
                            );

            TweetResponse response =
                    buildDeletedResponse(deletedTweet);


            return Result.success(response);
        }
        catch (ValidationException | ForbiddenException | ConflictException | NotFoundException e)
        {
            return Result.failure(e.getMessage());
        }
    }

    private TweetResponse buildDeletedResponse(TweetModel tweet)
    {
        UserModel author =
                userRepository.findById(tweet.getAuthorId())
                        .orElse(null);

        UserSummaryResponse authorSummary =
                author != null
                        ? UserSummaryResponseMapper.toResponse(author)
                        : null;

        TweetModel enrichedTweet = tweet.toBuilder()
                .likeCount(relationshipRepository.countLikesByTweetId(tweet.getId()))
                .replyCount(tweetRepository.countRepliesByTweetId(tweet.getId()))
                .retweetCount(tweetRepository.countRetweetsByTweetId(tweet.getId()))
                .build();

        return TweetMapper.toResponse(
                enrichedTweet,
                authorSummary,
                null,
                null,
                null,
                null
        );
    }
}