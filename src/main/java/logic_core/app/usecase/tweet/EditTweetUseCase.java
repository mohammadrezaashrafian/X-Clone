package logic_core.app.usecase.tweet;

import jakarta.transaction.Transactional;
import logic_core.app.cache.CacheInvalidation;
import logic_core.app.cache.CacheKeys;
import logic_core.app.dto.request.EditTweetRequest;
import logic_core.app.dto.response.MediaResponse;
import logic_core.app.dto.response.PollResponse;
import logic_core.app.dto.response.TweetResponse;
import logic_core.app.dto.response.UserSummaryResponse;
import logic_core.app.mapper.PollMapper;
import logic_core.app.dto.validator.TweetValidator;
import logic_core.app.mapper.TweetMapper;
import logic_core.app.mapper.UserSummaryResponseMapper;
import logic_core.app.security.AuthLockOrchestrator;
import logic_core.app.security.SessionUserContext;
import logic_core.common.exception.*;
import logic_core.common.result.Result;
import logic_core.common.util.TimeProvider;
import logic_core.domain.model.TweetModel;
import logic_core.domain.model.UserModel;
import logic_core.domain.policy.InteractionPolicy;
import logic_core.domain.repository.*;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EditTweetUseCase
{
    @NonNull private final TweetValidator validator;
    @NonNull private final InteractionPolicy interactionPolicy;
    @NonNull private final TweetRepository tweetRepository;
    @NonNull private final TweetEditRepository tweetEditRepository;
    @NonNull private final UserRepository userRepository;
    @NonNull private final AuthLockOrchestrator lockOrchestrator;
    @NonNull private final MediaRepository mediaRepository;
    @NonNull private final RelationshipRepository relationshipRepository;
    @NonNull private final PollRepository pollRepository;
    @NonNull private final CacheInvalidation cacheInvalidation;

    @Transactional
    public Result<TweetResponse> execute(EditTweetRequest request)
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

            validator.validateEditTweet(request.tweetId(), request.content());

            TweetModel tweet = tweetRepository.findActiveByIdForUpdate(request.tweetId())
                    .orElseThrow(() -> new NotFoundException("Tweet not found or unavailable."));

            interactionPolicy.validateEdit(currentUserId, tweet.getId(), tweet.getAuthorId());

            String newContent = request.content().trim();
            if (newContent.equals(tweet.getContent()))
            {
                return Result.success(buildTweetResponse(tweet));
            }

            tweetRepository.update(tweet.toBuilder().content(newContent).isEdited(true).build());
            tweetEditRepository.appendEditHistory(tweet.getId(), tweet.getContent());

            TweetModel savedTweet = tweetRepository.findById(tweet.getId())
                    .orElseThrow(() -> new RuntimeException("Failed to reload edited tweet."));

            // Invalidate the single-tweet cache for ALL viewers of this tweet
            // (pattern match) after the transaction commits. (V2.1 #19)
            cacheInvalidation.evictByPatternAfterCommit(CacheKeys.tweetPattern(tweet.getId()));

            return Result.success(buildTweetResponse(savedTweet));
        }
        catch (ValidationException | ForbiddenException | NotFoundException | ConflictException e)
        {
            return Result.failure(e.getMessage());
        }
        catch (Exception e)
        {
            return Result.failure("Unexpected error during edit: " + e.getMessage());
        }
    }


    private TweetResponse buildTweetResponse(TweetModel tweet)
    {
        UserModel author = userRepository.findById(tweet.getAuthorId())
                .orElseThrow(() -> new RuntimeException("Author not found."));

        UserSummaryResponse authorSummary = UserSummaryResponseMapper.toResponse(author);

        TweetResponse repliedTweetResponse = null;
        if (tweet.getRepliedToTweetId() != null)
        {
            repliedTweetResponse = tweetRepository.findById(tweet.getRepliedToTweetId())
                    .map(this::toShallowResponseWithCounts)
                    .orElse(null);
        }

        TweetResponse quotedTweetResponse = null;
        if (tweet.getQuotedTweetId() != null)
        {
            quotedTweetResponse = tweetRepository.findById(tweet.getQuotedTweetId())
                    .map(this::toShallowResponseWithCounts)
                    .orElse(null);
        }

        TweetResponse retweetedTweetResponse = null;
        if (tweet.getRetweetedTweetId() != null)
        {
            retweetedTweetResponse = tweetRepository.findById(tweet.getRetweetedTweetId())
                    .map(this::toShallowResponseWithCounts)
                    .orElse(null);
        }

        List<MediaResponse> mediaResponses =
                mediaRepository.findById(tweet.getId())
                        .stream()
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

        TweetModel enrichedTweet = enrichWithCounts(tweet);

        PollResponse poll = pollRepository.findByTweetId(tweet.getId())
                .map(PollMapper::toResponse)
                .orElse(null);

        return TweetMapper.toResponse(
                enrichedTweet,
                authorSummary,
                repliedTweetResponse,
                retweetedTweetResponse,
                mediaResponses,
                quotedTweetResponse,
                poll
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
                null,
                null,
                null
        );
    }
}