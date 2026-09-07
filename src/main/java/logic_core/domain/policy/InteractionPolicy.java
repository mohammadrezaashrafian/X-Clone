package logic_core.domain.policy;

import logic_core.common.exception.ConflictException;
import logic_core.common.exception.ForbiddenException;
import logic_core.common.exception.NotFoundException;
import logic_core.domain.model.TweetModel;
import logic_core.domain.model.UserModel;
import logic_core.domain.repository.MediaRepository;
import logic_core.domain.repository.RelationshipRepository;
import logic_core.domain.repository.TweetRepository;
import logic_core.domain.repository.UserRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class InteractionPolicy
{
    private final UserRepository userRepository;
    private final RelationshipRepository relationshipRepository;
    private final TweetRepository tweetRepository;

    public void validateCreate(String content,
                               UUID replyToId,
                               UUID quoteOfId,
                               OffsetDateTime scheduledAt,
                               UUID authorId)
    {
        requireNonNullId(authorId, "authorId");

        UserModel author = userRepository.findById(authorId)
                .orElseThrow(() -> new NotFoundException("Author does not exist."));

        if (!author.isActive())
        {
            throw new ForbiddenException("Inactive users cannot create tweets.");
        }

        if (replyToId != null)
        {
            validateInteractionTarget(replyToId, authorId, "reply");
        }

        if (quoteOfId != null)
        {
            validateInteractionTarget(quoteOfId, authorId, "quote");
        }
    }

    public void validateEdit(UUID userId, UUID tweetId, UUID tweetAuthorId)
    {
        requireNonNullId(userId, "userId");
        requireNonNullId(tweetId, "tweetId");
        requireNonNullId(tweetAuthorId, "tweetAuthorId");

        UserModel user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found."));

        if (!user.isActive())
        {
            throw new ForbiddenException("Inactive users cannot edit tweets.");
        }

        if (!tweetRepository.existsActiveById(tweetId))
        {
            throw new NotFoundException("Tweet not found or unavailable.");
        }

        if (!Objects.equals(userId, tweetAuthorId))
        {
            throw new ForbiddenException("You can only edit your own tweet.");
        }
    }

    private void validateInteractionTarget(UUID targetTweetId,
                                           UUID authorId,
                                           String type)
    {
        if (!tweetRepository.existsActiveById(targetTweetId))
        {
            throw new NotFoundException("The " + type + " target tweet does not exist.");
        }

        UserModel targetAuthor = tweetRepository.findActiveById(targetTweetId)
                .flatMap(t -> userRepository.findById(t.getAuthorId()))
                .orElseThrow(() -> new NotFoundException(
                        "Author of the " + type + " target tweet does not exist."));

        if (relationshipRepository.existsBlockRelation(authorId, targetAuthor.getId())
                || relationshipRepository.existsBlockRelation(targetAuthor.getId(), authorId))
        {
            throw new ForbiddenException(
                    "Cannot " + type + ": a block relation exists between users.");
        }

        if (relationshipRepository.existsMuteRelation(targetAuthor.getId(), authorId))
        {
            throw new ForbiddenException("Cannot " + type + ": muted by target author.");
        }
    }

    public void validateLike(UUID userId, UUID tweetId, UUID tweetAuthorId)
    {
        requireNonNullId(userId, "userId");
        requireNonNullId(tweetId, "tweetId");
        requireNonNullId(tweetAuthorId, "tweetAuthorId");

        validateBaseInteraction(userId, tweetAuthorId);

        if (relationshipRepository.hasLiked(userId, tweetId))
        {
            throw new ConflictException("Like relation already exists.");
        }
    }

    /**
     * Validates that a like/unlike TOGGLE operation is allowed.
     * Performs the same base interaction checks as {@link #validateLike}
     * (user active, author active, block barrier) but does NOT reject an
     * already-existing like — the toggle caller decides insert vs. delete.
     */
    public void validateLikeToggle(UUID userId, UUID tweetId, UUID tweetAuthorId)
    {
        requireNonNullId(userId, "userId");
        requireNonNullId(tweetId, "tweetId");
        requireNonNullId(tweetAuthorId, "tweetAuthorId");

        validateBaseInteraction(userId, tweetAuthorId);
    }

    public void validateUnlike(UUID userId, UUID tweetId, UUID tweetAuthorId)
    {
        requireNonNullId(userId, "userId");
        requireNonNullId(tweetId, "tweetId");
        requireNonNullId(tweetAuthorId, "tweetAuthorId");

        validateBlockBarrier(userId, tweetAuthorId);

        if (!relationshipRepository.hasLiked(userId, tweetId))
        {
            throw new NotFoundException("Like relation does not exist.");
        }
    }

    public void validateReply(UUID userId, UUID tweetAuthorId)
    {
        requireNonNullId(userId, "userId");
        requireNonNullId(tweetAuthorId, "tweetAuthorId");

        validateBaseInteraction(userId, tweetAuthorId);
    }

    public void validateRetweet(UUID userId, UUID tweetAuthorId, UUID tweetId)
    {
        requireNonNullId(userId, "userId");
        requireNonNullId(tweetAuthorId, "tweetAuthorId");
        requireNonNullId(tweetId, "tweetId");

        validateBaseInteraction(userId, tweetAuthorId);

        if (Objects.equals(userId, tweetAuthorId))
        {
            throw new ForbiddenException("You cannot retweet your own tweet.");
        }

        if (tweetRepository.isRetweetedByUser(tweetId, userId))
        {
            throw new ConflictException("You have already retweeted this tweet.");
        }
    }

    /**
     * Validates that a user may remove their own retweet of {@code tweetId}.
     * Performs the same base interaction barrier as {@link #validateRetweet}
     * (active users, block barrier) without the create-only duplicate and
     * self-retweet rules. Whether the user actually owns an active retweet is
     * decided by the atomic delete operation, not by a check-then-delete race.
     */
    public void validateUnretweet(UUID userId, UUID tweetAuthorId, UUID tweetId)
    {
        requireNonNullId(userId, "userId");
        requireNonNullId(tweetAuthorId, "tweetAuthorId");
        requireNonNullId(tweetId, "tweetId");

        validateBaseInteraction(userId, tweetAuthorId);
    }

    private void validateBaseInteraction(UUID userId, UUID tweetAuthorId)
    {
        UserModel user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found."));
        UserModel author = userRepository.findById(tweetAuthorId)
                .orElseThrow(() -> new NotFoundException("Tweet author not found."));

        if (!user.isActive())
        {
            throw new ForbiddenException("Inactive users cannot interact.");
        }

        if (!author.isActive())
        {
            throw new ForbiddenException("Cannot interact with content of an inactive user.");
        }

        validateBlockBarrier(userId, tweetAuthorId);
    }

    private void validateBlockBarrier(UUID userId, UUID tweetAuthorId)
    {
        if (relationshipRepository.existsBlockRelation(userId, tweetAuthorId) ||
                relationshipRepository.existsBlockRelation(tweetAuthorId, userId))
        {
            throw new ForbiddenException("Interaction is not allowed because a block relation exists between users.");
        }
    }

    private void requireNonNullId(UUID id, String name)
    {
        Objects.requireNonNull(id, name + " must not be null");
    }
}