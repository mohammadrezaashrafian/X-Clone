package logic_core.app.usecase.tweet;

import jakarta.transaction.Transactional;
import logic_core.app.dto.request.CreateTweetRequest;
import logic_core.app.dto.response.MediaResponse;
import logic_core.app.dto.response.PollResponse;
import logic_core.app.dto.response.TweetResponse;
import logic_core.app.dto.response.UserSummaryResponse;
import logic_core.app.dto.validator.TweetValidator;
import logic_core.app.mapper.TweetMapper;
import logic_core.app.mapper.UserSummaryResponseMapper;
import logic_core.app.security.AuthLockOrchestrator;
import logic_core.app.security.SessionUserContext;
import logic_core.app.service.HashtagApplicationService;
import logic_core.app.service.MentionApplicationService;
import logic_core.app.service.NotificationApplicationService;
import logic_core.app.service.PollApplicationService;
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
import java.util.Comparator;
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
    @NonNull private final HashtagApplicationService hashtagService;
    @NonNull private final MentionApplicationService mentionService;
    @NonNull private final PollApplicationService pollService;

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
                    request.poll() != null,
                    request.scheduledAt()
            );

            // Validate the poll before any persistence so that an invalid poll
            // fails atomically (no tweet row is written) with existing
            // validation/error conventions.
            if (request.poll() != null)
            {
                pollService.validate(request.poll());
            }

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
                    attachUploadedMedia(
                            request.mediaUrls(),
                            savedTweet.getId(),
                            currentUserId
                    );

            hashtagService.processTweetHashtags(
                    savedTweet.getContent(),
                    savedTweet.getId()
            );

            mentionService.processTweetMentions(
                    savedTweet.getContent(),
                    savedTweet.getId()
            );

            PollResponse pollResponse = null;
            if (request.poll() != null)
            {
                // Poll creation happens in the same transaction as tweet
                // creation (CreateTweetUseCase is @Transactional).
                pollResponse = pollService.createPoll(
                        request.poll(),
                        savedTweet.getId()
                );
            }

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

            TweetResponse response = buildTweetResponse(
                    savedTweet,
                    mediaModels,
                    pollResponse
            );



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

    /**
     * Attaches media referenced by the request to the tweet.
     *
     * <p>Tokens that parse as a UUID are treated as uploaded-media ids
     * (V2.1 #6): the existing media row must exist, belong to the
     * authenticated actor, and be unattached; the existing row is then
     * attached rather than duplicated. Tokens that are not UUIDs keep the
     * legacy URL-based contract and create new media rows via
     * {@link MediaRepository#createMedia}.
     */
    private List<MediaModel> attachUploadedMedia(
            List<String> uploadTokens,
            UUID tweetId,
            UUID actorId)
    {
        if (uploadTokens == null || uploadTokens.isEmpty())
        {
            return Collections.emptyList();
        }

        List<MediaModel> attached = new ArrayList<>();
        List<String> legacyUrls = new ArrayList<>();

        for (String token : uploadTokens)
        {
            UUID mediaId = parseMediaId(token);
            if (mediaId == null)
            {
                legacyUrls.add(token);
                continue;
            }

            MediaModel media = mediaRepository.findById(mediaId)
                    .orElseThrow(() -> new NotFoundException(
                            "Media not found: nonexistent media id " + mediaId));

            if (!media.isOwnedBy(actorId))
            {
                throw new ForbiddenException(
                        "Media ownership violation: media does not belong to the authenticated user");
            }

            if (media.isAttachedToTweet())
            {
                throw new ConflictException(
                        "Media is already attached to another tweet");
            }

            if (!mediaRepository.attachToTweet(mediaId, tweetId))
            {
                throw new ConflictException(
                        "Media is already attached to another tweet");
            }

            // Reflect the attachment on the in-memory model so the response
            // carries the attached media row (no duplicate row is created).
            media.attachToTweet(tweetId);
            attached.add(media);
        }

        if (!legacyUrls.isEmpty())
        {
            attached.addAll(mediaRepository.createMedia(tweetId, legacyUrls));
        }

        attached.sort(Comparator.comparing(m -> (int) m.getDisplayOrder()));
        return attached;
    }

    private static UUID parseMediaId(String token)
    {
        if (token == null)
        {
            return null;
        }
        try
        {
            return UUID.fromString(token.trim());
        }
        catch (IllegalArgumentException e)
        {
            return null;
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
            List<MediaModel> mediaModels,
            PollResponse poll)
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
                quotedTweetResponse,
                poll
        );
    }
}