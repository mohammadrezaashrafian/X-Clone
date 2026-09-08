package logic_core.app.service;

import logic_core.domain.model.TweetMention;
import logic_core.domain.model.UserModel;
import logic_core.domain.model.notification.NotificationType;
import logic_core.domain.repository.MentionRepository;
import logic_core.domain.repository.UserRepository;
import logic_core.domain.service.MentionExtractor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves and persists {@code @username} mentions for user-authored tweet
 * content.
 *
 * <p>Mention tokens are extracted from the tweet content, resolved against the
 * user registry (exact, case-sensitive match, active and not deleted only) and
 * one {@code tweet_mentions} row is written per resolved user. Unknown or
 * inactive usernames are silently skipped — the text is preserved but no
 * invalid relationship row is created. Duplicate mentions within one tweet are
 * collapsed by the extractor, and the database primary key additionally
 * prevents duplicate relationships.
 *
 * <p>Callers invoke this in the same transaction as tweet creation, so mention
 * rows are written only when the tweet itself was persisted. As of V2.1 #7,
 * every resolved mentioned user also receives a MENTION notification from the
 * tweet author (self-mentions are skipped by the notification service).
 */
@Service
@RequiredArgsConstructor
public class MentionApplicationService
{
    @NonNull private final MentionRepository mentionRepository;
    @NonNull private final UserRepository userRepository;
    @NonNull private final NotificationApplicationService notificationService;

    public void processTweetMentions(String content, UUID tweetId, UUID authorId)
    {
        if (tweetId == null)
        {
            return;
        }

        List<String> usernames = MentionExtractor.extract(content);

        for (String username : usernames)
        {
            resolveActiveUser(username)
                    .ifPresent(userId ->
                    {
                        mentionRepository.attachToTweet(
                                TweetMention.create(userId, tweetId)
                        );

                        notificationService.notify(
                                userId,
                                authorId,
                                tweetId,
                                NotificationType.MENTION
                        );
                    });
        }
    }

    private Optional<UUID> resolveActiveUser(String username)
    {
        return userRepository.findByUsername(username)
                .filter(user -> !user.isDeleted() && user.isActive())
                .map(UserModel::getId);
    }
}