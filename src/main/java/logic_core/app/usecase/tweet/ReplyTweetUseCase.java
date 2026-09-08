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
import logic_core.app.service.HashtagApplicationService;
import logic_core.app.service.MentionApplicationService;
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
import java.util.ArrayList;
import java.util.Comparator;
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
    @NonNull private final HashtagApplicationService hashtagService;
    @NonNull private final MentionApplicationService mentionService;


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



            List<MediaModel> mediaModels =
                    attachUploadedMedia(
                            request.mediaUrls(),
                            savedReply.getId(),
                            currentUserId
                    );

            hashtagService.processTweetHashtags(
                    savedReply.getContent(),
                    savedReply.getId()
            );

            mentionService.processTweetMentions(
                    savedReply.getContent(),
                    savedReply.getId(),
                    currentUserId
            );



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



    /**
     * Attaches media referenced by the request to the reply.
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
            return List.of();
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
                null,
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
                null,
                null
        );
    }
}