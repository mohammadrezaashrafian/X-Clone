package logic_core.app.usecase.tweet;

import jakarta.transaction.Transactional;
import logic_core.app.dto.request.CreateTweetRequest;
import logic_core.app.dto.response.MediaResponse;
import logic_core.app.dto.response.TweetResponse;
import logic_core.app.dto.response.UserSummaryResponse;
import logic_core.app.dto.validator.TweetValidator;
import logic_core.app.mapper.TweetMapper;
import logic_core.app.mapper.UserSummaryResponseMapper;
import logic_core.app.security.AuthLockOrchestrator;
import logic_core.app.security.SessionUserContext;
import logic_core.app.service.NotificationApplicationService;
import logic_core.common.exception.ConflictException;
import logic_core.common.exception.ForbiddenException;
import logic_core.common.exception.NotFoundException;
import logic_core.common.exception.ValidationException;
import logic_core.common.result.Result;
import logic_core.common.util.TimeProvider;
import logic_core.domain.model.MediaModel;
import logic_core.domain.model.TweetModel;
import logic_core.domain.model.UserModel;
import logic_core.domain.model.notification.NotificationType;
import logic_core.domain.policy.InteractionPolicy;
import logic_core.domain.repository.MediaRepository;
import logic_core.domain.repository.TweetRepository;
import logic_core.domain.repository.UserRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CreateTweetUseCase
{
    @NonNull private final TweetValidator validator;
    @NonNull private final InteractionPolicy interactionPolicy;
    @NonNull private final TweetRepository tweetRepository;
    @NonNull private final UserRepository userRepository;
    @NonNull private final TimeProvider timeProvider;
    @NonNull private final AuthLockOrchestrator lockOrchestrator;
    @NonNull private final MediaRepository mediaRepository;
    @NonNull private final NotificationApplicationService notificationService;

    @Transactional
    public Result<TweetResponse> execute(@NonNull CreateTweetRequest request)
    {
        try
        {
            SessionUserContext context = lockOrchestrator.lockAndGetContextByToken(request.sessionToken());

            UserModel currentUser = context.lockedUser();
            UUID currentUserId = currentUser.getId();

            validator.validateCreateTweet(
                    request.content(),
                    request.replyToId(),
                    request.quoteOfId(),
                    request.mediaUrls(),
                    false,
                    request.scheduledAt()
            );

            interactionPolicy.validateCreate(
                    request.content(),
                    request.replyToId(),
                    request.quoteOfId(),
                    request.scheduledAt(),
                    currentUserId
            );

            TweetModel tweet = createTweetEntity(
                    request,
                    currentUserId
            );

            TweetModel savedTweet =
                    tweetRepository.save(tweet)
                            .orElseThrow(() ->
                                    new RuntimeException("Failed to save tweet.")
                            );

            List<MediaModel> mediaModels =
                    request.mediaUrls() == null || request.mediaUrls().isEmpty()
                            ? Collections.emptyList()
                            : mediaRepository.createMedia(
                            savedTweet.getId(),
                            request.mediaUrls()
                    );

            if (savedTweet.getQuotedTweetId() != null)
            {
                tweetRepository.findById(savedTweet.getQuotedTweetId())
                        .ifPresent(quoted ->
                                notificationService.notify(
                                        quoted.getAuthorId(),
                                        currentUserId,
                                        quoted.getId(),
                                        NotificationType.QUOTE
                                )
                        );
            }

            TweetResponse response = buildTweetResponse(savedTweet, mediaModels);



            try
            {

            }
            catch (Exception e)
            {
                e.printStackTrace();
                throw e;
            }

            return Result.success(response);
        }
        catch (ValidationException | ForbiddenException | ConflictException | NotFoundException e)
        {
            return Result.failure(e.getMessage());
        }
    }

    private TweetModel createTweetEntity(
            CreateTweetRequest request,
            UUID authorId)
    {
        return TweetModel.builder()
                .id(UUID.randomUUID())
                .authorId(authorId)
                .content(request.content())
                .repliedToTweetId(request.replyToId())
                .quotedTweetId(request.quoteOfId())
                .scheduledAt(request.scheduledAt())
                .publishedAt(timeProvider.now())
                .createdAt(timeProvider.now())
                .build();
    }

    private TweetResponse buildTweetResponse(
            TweetModel tweet,
            List<MediaModel> mediaModels)
    {
        UserModel author =
                userRepository.findById(tweet.getAuthorId())
                        .orElseThrow(() ->
                                new RuntimeException("Author not found.")
                        );

        UserSummaryResponse authorSummary =
                UserSummaryResponseMapper.toResponse(author);

        TweetResponse repliedTweetResponse = null;

        if (tweet.getRepliedToTweetId() != null)
        {
            repliedTweetResponse =
                    tweetRepository.findById(tweet.getRepliedToTweetId())
                            .map(parent ->
                                    TweetMapper.toResponse(
                                            parent,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null
                                    )
                            )
                            .orElse(null);
        }

        TweetResponse quotedTweetResponse = null;

        if (tweet.getQuotedTweetId() != null)
        {
            quotedTweetResponse =
                    tweetRepository.findById(tweet.getQuotedTweetId())
                            .map(parent ->
                                    TweetMapper.toResponse(
                                            parent,
                                            null,
                                            null,
                                            null,
                                            null,
                                            null
                                    )
                            )
                            .orElse(null);
        }

        List<MediaResponse> mediaResponses =
                mediaModels.stream()
                        .map(media ->
                                new MediaResponse(
                                        media.getMediaId(),
                                        media.getMediaUrl(),
                                        media.getOriginalFilename(),
                                        media.getFileSizeBytes(),
                                        media.getMediaType(),
                                        media.getDisplayOrder()
                                )
                        )
                        .toList();

        return TweetMapper.toResponse(
                tweet,
                authorSummary,
                repliedTweetResponse,
                null,
                mediaResponses,
                quotedTweetResponse
        );
    }
}