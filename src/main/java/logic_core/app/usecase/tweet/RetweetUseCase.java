package logic_core.app.usecase.tweet;

import jakarta.transaction.Transactional;
import logic_core.app.dto.request.RetweetRequest;
import logic_core.app.dto.response.TweetResponse;
import logic_core.app.dto.response.UserSummaryResponse;
import logic_core.app.mapper.TweetMapper;
import logic_core.app.mapper.UserSummaryResponseMapper;
import logic_core.app.security.AuthLockOrchestrator;
import logic_core.app.security.SessionUserContext;
import logic_core.app.service.NotificationApplicationService;
import logic_core.common.exception.AppException;
import logic_core.common.exception.DatabaseException;
import logic_core.common.exception.NotFoundException;
import logic_core.common.result.Result;
import logic_core.common.util.TimeProvider;
import logic_core.domain.model.TweetModel;
import logic_core.domain.model.notification.NotificationType;
import logic_core.domain.policy.InteractionPolicy;
import logic_core.domain.repository.RelationshipRepository;
import logic_core.domain.repository.TweetRepository;
import logic_core.domain.repository.UserRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RetweetUseCase
{
    @NonNull
    private final InteractionPolicy interactionPolicy;

    @NonNull
    private final TweetRepository tweetRepository;

    @NonNull
    private final UserRepository userRepository;

    @NonNull
    private final RelationshipRepository relationshipRepository;

    @NonNull
    private final TimeProvider timeProvider;

    @NonNull
    private final AuthLockOrchestrator lockOrchestrator;

    @NonNull
    private final NotificationApplicationService notificationService;


    @Transactional
    public Result<TweetResponse> execute(RetweetRequest request)
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

            UUID currentUserId =
                    context.lockedUser().getId();

            TweetModel originalTweet =
                    tweetRepository.findActiveByIdForUpdate(
                                    request.tweetId()
                            )
                            .orElseThrow(() ->
                                    new NotFoundException(
                                            "Tweet not found or deleted."
                                    )
                            );

            interactionPolicy.validateRetweet(
                    currentUserId,
                    originalTweet.getAuthorId(),
                    originalTweet.getId()
            );

            OffsetDateTime now = timeProvider.now();

            TweetModel retweet =
                    TweetModel.builder()
                            .id(UUID.randomUUID())
                            .authorId(currentUserId)
                            .retweetedTweetId(originalTweet.getId())
                            .publishedAt(now)
                            .createdAt(now)
                            .updatedAt(now)
                            .build();

            TweetModel savedRetweet =
                    tweetRepository.save(retweet)
                            .orElseThrow(() ->
                                    new DatabaseException(
                                            "Failed to save retweet."
                                    )
                            );

            TweetModel updatedOriginal =
                    originalTweet.toBuilder()
                            .retweetCount(
                                    originalTweet.getRetweetCount() + 1
                            )
                            .updatedAt(now)
                            .build();

            tweetRepository.update(updatedOriginal);

            notificationService.notify(
                    originalTweet.getAuthorId(),
                    currentUserId,
                    originalTweet.getId(),
                    NotificationType.RETWEET
            );


            return Result.success(
                    toResponse(savedRetweet)
            );
        }
        catch (AppException e)
        {
            return Result.failure(
                    e.getMessage()
            );
        }
        catch (Exception e)
        {
            return Result.failure(
                    "Failed to process retweet."
            );
        }
    }


    private TweetResponse toResponse(
            TweetModel tweet
    )
    {
        UserSummaryResponse authorSummary =
                userRepository.findById(tweet.getAuthorId())
                        .map(UserSummaryResponseMapper::toResponse)
                        .orElse(null);

        TweetModel enrichedTweet = enrichWithCounts(tweet);

        TweetResponse retweetedTweet =
                tweet.getRetweetedTweetId() != null
                        ?
                        tweetRepository.findById(tweet.getRetweetedTweetId())
                                .map(this::toShallowResponseWithCounts)
                                .orElse(null)
                        :
                        null;

        return TweetMapper.toResponse(
                enrichedTweet,
                authorSummary,
                null,
                retweetedTweet,
                List.of(),
                null
        );
    }


    private TweetModel enrichWithCounts(TweetModel tweet)
    {
        return tweet.toBuilder()
                .likeCount(relationshipRepository.countLikesByTweetId(tweet.getId()))
                .replyCount(tweetRepository.countRepliesByTweetId(tweet.getId()))
                .retweetCount(tweetRepository.countRetweetsByTweetId(tweet.getId()))
                .build();
    }


    private TweetResponse toShallowResponseWithCounts(
            TweetModel tweet
    )
    {
        return TweetMapper.toResponse(
                enrichWithCounts(tweet),
                null,
                null,
                null,
                List.of(),
                null
        );
    }
}