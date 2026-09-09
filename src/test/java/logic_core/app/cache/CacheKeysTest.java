package logic_core.app.cache;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cache key construction (V2.1 #19): deterministic, namespaced, and
 * collision-free between resources.
 */
class CacheKeysTest
{
    @Test
    void userProfileKeyIsNamespacedAndIdStable()
    {
        UUID id = UUID.fromString("6f1a5d10-0000-4000-8000-000000000001");

        assertThat(CacheKeys.userProfile(id))
                .isEqualTo("xc:user:profile:6f1a5d10-0000-4000-8000-000000000001");
        assertThat(CacheKeys.userProfile(id)).isEqualTo(CacheKeys.userProfile(id));
    }

    @Test
    void tweetKeyIncludesActorSoViewerScopedEntriesNeverCollide()
    {
        UUID actor = UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID other = UUID.fromString("22222222-2222-4222-8222-222222222222");
        UUID tweet = UUID.fromString("33333333-3333-4333-8333-333333333333");

        assertThat(CacheKeys.tweet(actor, tweet))
                .isEqualTo("xc:tweet:11111111-1111-4111-8111-111111111111:33333333-3333-4333-8333-333333333333");
        assertThat(CacheKeys.tweet(actor, tweet)).isNotEqualTo(CacheKeys.tweet(other, tweet));
        assertThat(CacheKeys.tweet(actor, tweet)).isNotEqualTo(CacheKeys.tweet(actor, other));
    }

    @Test
    void tweetPatternMatchesEveryActorViewOfOneTweet()
    {
        UUID tweet = UUID.fromString("33333333-3333-4333-8333-333333333333");

        assertThat(CacheKeys.tweetPattern(tweet))
                .isEqualTo("xc:tweet:*:33333333-3333-4333-8333-333333333333");

        // Any actor key for this tweet must match the pattern.
        String actorKey = CacheKeys.tweet(
                UUID.fromString("44444444-4444-4444-8444-444444444444"), tweet);
        assertThat(actorKey).matches(CacheKeys.tweetPattern(tweet).replace("*", ".*"));
    }

    @Test
    void trendingKeyEncodesLimit()
    {
        assertThat(CacheKeys.trendingHashtags(10)).isEqualTo("xc:trending:hashtags:10");
        assertThat(CacheKeys.trendingHashtags(10)).isNotEqualTo(CacheKeys.trendingHashtags(50));
    }

    @Test
    void resourcesNeverCollideAcrossNamespaces()
    {
        UUID id = UUID.randomUUID();

        assertThat(CacheKeys.userProfile(id))
                .isNotEqualTo(CacheKeys.tweet(id, id))
                .isNotEqualTo(CacheKeys.trendingHashtags(10));
    }
}