package logic_core.app.service;

import logic_core.common.util.TimeProvider;
import logic_core.domain.model.HashtagModel;
import logic_core.domain.model.TweetHashtag;
import logic_core.domain.repository.HashtagRepository;
import logic_core.domain.service.HashtagExtractor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persists hashtags for user-authored tweet content.
 *
 * <p>Hashtags are always derived from the tweet content — never from
 * client-supplied identifiers. {@link #processTweetHashtags(String, UUID)}
 * extracts the distinct normalized tags, finds or creates the {@code hashtags}
 * rows, and attaches one {@code tweet_hashtags} row per distinct tag (with the
 * occurrence count inside the tweet). Callers invoke this in the same
 * transaction as tweet creation, so hashtag rows are written only when the
 * tweet itself was persisted.
 */
@Service
@RequiredArgsConstructor
public class HashtagApplicationService
{
    @NonNull private final HashtagRepository hashtagRepository;
    @NonNull private final TimeProvider timeProvider;

    public void processTweetHashtags(String content, UUID tweetId)
    {
        Map<String, Integer> tagsWithCounts =
                HashtagExtractor.extractWithCounts(content);

        if (tagsWithCounts.isEmpty() || tweetId == null)
        {
            return;
        }

        Map<String, HashtagModel> existingByTag = new HashMap<>();
        hashtagRepository.findByTags(tagsWithCounts.keySet())
                .forEach(hashtag -> existingByTag.put(hashtag.getTag(), hashtag));

        for (Map.Entry<String, Integer> entry : tagsWithCounts.entrySet())
        {
            String tag = entry.getKey();

            HashtagModel hashtag = existingByTag.get(tag);

            if (hashtag == null)
            {
                hashtag = hashtagRepository.save(
                        HashtagModel.create(tag, timeProvider.now())
                );
                existingByTag.put(tag, hashtag);
            }

            hashtagRepository.attachToTweet(
                    TweetHashtag.create(
                            hashtag.getId(),
                            tweetId,
                            entry.getValue()
                    )
            );
        }
    }

    /**
     * Resolves the canonical form of a caller-supplied tag, creating the
     * hashtag row if it does not exist yet. Used by hashtag follow/unfollow.
     *
     * @return the persisted hashtag
     */
    public HashtagModel findOrCreate(String normalizedTag)
    {
        return hashtagRepository.findByTag(normalizedTag)
                .orElseGet(() -> hashtagRepository.save(
                        HashtagModel.create(normalizedTag, timeProvider.now())
                ));
    }

    public List<HashtagModel> findByTags(List<String> normalizedTags)
    {
        return hashtagRepository.findByTags(normalizedTags);
    }
}