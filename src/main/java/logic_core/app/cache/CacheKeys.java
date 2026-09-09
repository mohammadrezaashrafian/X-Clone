package logic_core.app.cache;

import java.util.UUID;

/**
 * Deterministic, namespaced cache key construction (V2.1 #19).
 *
 * <p>Every key starts with the {@code xc:} namespace so cached resources can
 * never collide with each other or with anything else stored in the same
 * Redis instance. The pattern is {@code xc:<resource>:<selector>...}.
 *
 * <ul>
 *   <li>{@code xc:user:profile:{userId}} — public profile projection for one
 *       user (viewer-independent).</li>
 *   <li>{@code xc:tweet:{actorId}:{tweetId}} — single-tweet read projection,
 *       scoped by the authenticated actor because visibility (block/mute) is
 *       evaluated per viewer inside the query. The eviction pattern
 *       {@code xc:tweet:*:{tweetId}} clears all viewers for one tweet.</li>
 *   <li>{@code xc:trending:hashtags:{limit}} — global trending ranking for a
 *       given clamped limit (viewer-independent).</li>
 * </ul>
 */
public final class CacheKeys
{
    public static final String NAMESPACE = "xc";

    private CacheKeys()
    {
    }

    public static String userProfile(UUID userId)
    {
        return NAMESPACE + ":user:profile:" + userId;
    }

    public static String tweet(UUID actorId, UUID tweetId)
    {
        return NAMESPACE + ":tweet:" + actorId + ":" + tweetId;
    }

    /**
     * Pattern matching every actor's cached view of one tweet; used to evict
     * the single-tweet cache for all viewers after edit/delete.
     */
    public static String tweetPattern(UUID tweetId)
    {
        return NAMESPACE + ":tweet:*:" + tweetId;
    }

    public static String trendingHashtags(int limit)
    {
        return NAMESPACE + ":trending:hashtags:" + limit;
    }
}