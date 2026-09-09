package logic_core.app.cache;

import java.time.Duration;

/**
 * TTL policy for cached data (V2.1 #19).
 *
 * <p>Every cache entry expires; nothing is retained indefinitely. TTLs are
 * chosen per data type:
 *
 * <ul>
 *   <li><b>PROFILE (60s)</b> — profile reads are frequent, and profile content
 *       mutations (bio/avatar/banner/display name/username) evict the entry
 *       immediately after commit. The 60s bound additionally limits the
 *       staleness of aggregated follower/following/tweet <i>counts</i>, which
 *       are intentionally not evicted on every follow/like (see strategy).</li>
 *   <li><b>TWEET (60s)</b> — single-tweet reads; content/visibility mutations
 *       (edit/delete) evict immediately after commit. Like/reply/retweet
 *       <i>counts</i> inside the projection are TTL-bounded for the same
 *       reason.</li>
 *   <li><b>TRENDING (60s)</b> — the global 24h ranking is a window aggregate;
 *       a bounded 60s staleness is part of the documented [V2.1 #9] contract
 *       and far smaller than the 24h window granularity.</li>
 * </ul>
 *
 * <p>Correctness rule: if PostgreSQL contains new authoritative state, Redis
 * must never serve a known-invalid value indefinitely. Content and visibility
 * mutations always evict; count-only aggregates are bounded by these TTLs.
 */
public final class CachePolicy
{
    public static final Duration PROFILE = Duration.ofSeconds(60);
    public static final Duration TWEET = Duration.ofSeconds(60);
    public static final Duration TRENDING = Duration.ofSeconds(60);

    private CachePolicy()
    {
    }
}