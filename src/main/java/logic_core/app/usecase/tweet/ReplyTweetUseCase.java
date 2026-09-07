package logic_core.app.usecase.tweet;

import jakarta.transaction.Transactional;
import logic_core.app.dto.request.ReplyTweetRequest;
import logic_core.app.dto.response.MediaResponse;
import logic_core.app.dto.response.TweetResponse;
import logic_core.app.dto.response.UserSummaryResponse;
import logic_core.app.dto.validator.TweetValidator;
import logic_core.app.mapper.TweetMapper;
import logic_core.app.mapper.UserSummaryResponseMapper;
import logic_core.app.security.AuthLockOrchestrator;
import logic_core.app.service.NotificationApplicationService;
import logic_core.app.security.SessionUserContext;
import logic_core.common.exception.*;
import logic_core.common.result.Result;
import logic_core.common.util.TimeProvider;
import logic_core.domain.model.MediaModel;
import logic_core.domain.model.TweetModel;
import logic_core.domain.model.notification.NotificationType;
import logic_core.domain.policy.InteractionPolicy;
import logic_core.domain.repository.MediaRepository;
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
public class ReplyTweetUseCase
{

    @NonNull private final InteractionPolicy interactionPolicy;
    @NonNull private final TweetRepository tweetRepository;
    @NonNull private final UserRepository userRepository;
    @NonNull private final TweetValidator tweetValidator;
    @NonNull private final TimeProvider timeProvider;
    @NonNull private final AuthLockOrchestrator lockOrchestrator;
    @NonNull private final MediaRepository mediaRepository;
    @NonNull private final RelationshipRepository relationshipRepository;
    @NonNull private final NotificationApplicationService notificationService;


    @Transactional
    public Result<TweetResponse> execute(ReplyTweetRequest request)
    {
        if (request == null)
        {
            return Result.failure("Request cannot be null.");
        }


        try
        {
            SessionUserContext context =
                    lockOrchestrator.lockAndGetContextByToken(
                            request.sessionToken()
                    );


            UUID currentUserId = context.lockedUser().getId();


            tweetValidator.validateReplyTweet(
                    request.parentTweetId(),
                    request.text(),
                    request.mediaUrls()
            );


            TweetModel parentTweet =
                    tweetRepository.findActiveByIdForUpdate(
                                    request.parentTweetId()
                            )
                            .orElseThrow(() ->
                                    new NotFoundException(
                                            "Parent tweet not found or deleted."
                                    )
                            );


            interactionPolicy.validateReply(
                    currentUserId,
                    parentTweet.getAuthorId()
            );


            OffsetDateTime now = timeProvider.now();


            TweetModel reply =
                    TweetModel.builder()
                            .id(UUID.randomUUID())
                            .authorId(currentUserId)
                            .content(request.text())
                            .repliedToTweetId(parentTweet.getId())
                            .publishedAt(now)
                            .createdAt(now)
                            .updatedAt(now)
                            .build();



            TweetModel savedReply =
                    tweetRepository.save(reply)
                            .orElseThrow(() ->
                                    new DatabaseException(
                                            "Failed to save reply."
                                    )
                            );



            List<MediaModel> mediaModels = List.of();


            if (request.mediaUrls() != null &&
                    !request.mediaUrls().isEmpty())
            {
                mediaModels =
                        mediaRepository.createMedia(
                                savedReply.getId(),
                                request.mediaUrls()
                        );
            }



            TweetModel updatedParent =
                    parentTweet.toBuilder()
                            .replyCount(parentTweet.getReplyCount() + 1)
                            .updatedAt(now)
                            .build();


            tweetRepository.update(updatedParent);

            notificationService.notify(
                    parentTweet.getAuthorId(),
                    currentUserId,
                    parentTweet.getId(),
                    NotificationType.REPLY
            );


            return Result.success(
                    toResponse(
                            savedReply,
                            mediaModels
                    )
            );

        }
        catch (AppException e)
        {
            return Result.failure(e.getMessage());
        }
        catch (Exception e)
        {
            return Result.failure(
                    "An unexpected error occurred while replying."
            );
        }
    }



    private TweetResponse toResponse(
            TweetModel tweet,
            List<MediaModel> mediaModels
    )
    {

        UserSummaryResponse authorSummary =
                userRepository.findById(tweet.getAuthorId())
                        .map(UserSummaryResponseMapper::toResponse)
                        .orElse(null);



        TweetResponse repliedTo =
                tweet.getRepliedToTweetId() != null
                        ?
                        tweetRepository.findActiveById(
                                        tweet.getRepliedToTweetId()
                                )
                                .map(this::toShallowResponseWithCounts)
                                .orElse(null)
                        :
                        null;



        List<MediaResponse> media =
                mediaModels.stream()
                        .map(m ->
                                new MediaResponse(
                                        m.getMediaId(),
                                        m.getMediaUrl(),
                                        m.getOriginalFilename(),
                                        m.getFileSizeBytes(),
                                        m.getMediaType(),
                                        m.getDisplayOrder()
                                )
                        )
                        .toList();



        return TweetMapper.toResponse(
                tweet,
                authorSummary,
                repliedTo,
                null,
                media,
                null
        );
    }



    private TweetResponse toShallowResponseWithCounts(
            TweetModel tweet
    )
    {
        TweetModel enriched = tweet.toBuilder()
                .likeCount(relationshipRepository.countLikesByTweetId(tweet.getId()))
                .replyCount(tweetRepository.countRepliesByTweetId(tweet.getId()))
                .retweetCount(tweetRepository.countRetweetsByTweetId(tweet.getId()))
                .build();

        return TweetMapper.toResponse(
                enriched,
                null,
                null,
                null,
                null,
                null
        );
    }
}