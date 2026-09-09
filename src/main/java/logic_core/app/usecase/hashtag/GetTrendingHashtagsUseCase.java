package logic_core.app.usecase.hashtag;

import logic_core.app.cache.CacheJsonCodec;
import logic_core.app.cache.CacheKeys;
import logic_core.app.cache.CachePolicy;
import logic_core.app.cache.CacheService;
import logic_core.app.dto.request.GetTrendingHashtagsRequest;
import logic_core.app.dto.response.TrendingHashtagsResponse;
import logic_core.app.security.AuthLockOrchestrator;
import logic_core.common.exception.AppException;
import logic_core.common.exception.ValidationException;
import logic_core.common.result.Result;
import logic_core.common.util.TimeProvider;
import logic_core.domain.model.TrendingHashtag;
import logic_core.domain.repository.HashtagRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Deterministic trending-hashtags ranking (TRENDING_HASHTAGS, [V2.1 #9]).
 *
 * <p><b>Supported window</b> — the trailing 24 hours, as the half-open
 * interval {@code [windowStart, windowEnd)} on {@code tweets.published_at},
 * where both boundaries are computed once per call from the same clock that
 * stamps {@code published_at} ({@link TimeProvider#now()}). A tweet published
 * exactly at {@code windowStart} counts; exactly at {@code windowEnd} does
 * not.
 *
 * <p><b>Ranking formula</b> — number of distinct qualifying tweets per
 * hashtag inside the window (soft-deleted tweets, deleted authors and
 * retweet markers excluded), descending. The per-tweet {@code usage_count}
 * (in-tweet tag occurrences) is deliberately not summed. Ties break by the
 * canonical tag ascending, which is unique, so the ordering is a stable
 * total order and repeated calls over unchanged data return identical
 * results.
 *
 * <p>The actor is derived from the authenticated session token, never from a
 * caller-supplied field. Trending is a global ranking — no per-actor
 * visibility filtering applies.
 */
@Service
@RequiredArgsConstructor
public class GetTrendingHashtagsUseCase
{
    private static final Logger log = LoggerFactory.getLogger(GetTrendingHashtagsUseCase.class);

    /** The one explicitly supported trending window, in hours. */
    static final int WINDOW_HOURS = 24;

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;

    @NonNull private final HashtagRepository hashtagRepository;
    @NonNull private final AuthLockOrchestrator lockOrchestrator;
    @NonNull private final TimeProvider timeProvider;
    @NonNull private final CacheService cacheService;
    @NonNull private final CacheJsonCodec cacheJsonCodec;

    public Result<TrendingHashtagsResponse> execute(GetTrendingHashtagsRequest request)
    {
        try
        {
            lockOrchestrator.lockAndGetContextByToken(request.sessionToken());

            int limit = clampLimit(request.limit());

            // Trending is a global ranking (no per-actor visibility), so the
            // cache key only needs the clamped limit. A bounded staleness of
            // CachePolicy.TRENDING (60s) is part of the documented trending
            // contract — far smaller than the 24h window granularity.
            // (V2.1 #19)
            String cacheKey = CacheKeys.trendingHashtags(limit);

            String cached = cacheService.get(cacheKey);
            if (cached != null)
            {
                TrendingHashtagsResponse cachedResponse = safeFromJson(cached, cacheKey);
                if (cachedResponse != null)
                {
                    return Result.success(cachedResponse);
                }
            }

            OffsetDateTime windowEnd = timeProvider.now();
            OffsetDateTime windowStart = windowEnd.minusHours(WINDOW_HOURS);

            List<TrendingHashtag> trending =
                    hashtagRepository.findTrending(windowStart, windowEnd, limit);

            long totalItems =
                    hashtagRepository.countTrendingHashtags(windowStart, windowEnd);

            List<TrendingHashtagsResponse.TrendingHashtagItem> items = new java.util.ArrayList<>();
            for (int i = 0; i < trending.size(); i++)
            {
                TrendingHashtag entry = trending.get(i);
                items.add(new TrendingHashtagsResponse.TrendingHashtagItem(
                        entry.tag(),
                        entry.score(),
                        i + 1
                ));
            }

            TrendingHashtagsResponse response =
                    TrendingHashtagsResponse.builder()
                            .items(items)
                            .totalItems(totalItems)
                            .windowDescription("last_24_hours_[start,end)")
                            .build();

            cacheService.put(cacheKey, cacheJsonCodec.toJson(response), CachePolicy.TRENDING);

            return Result.success(response);
        }
        catch (AppException e)
        {
            return Result.failure(e.getMessage());
        }
    }

    /**
     * Returns null (treating the entry as a miss) when a cached value cannot
     * be decoded, so corrupt cache data can never turn a healthy database read
     * into a failure response.
     */
    private TrendingHashtagsResponse safeFromJson(String json, String cacheKey)
    {
        try
        {
            return cacheJsonCodec.fromJson(json, TrendingHashtagsResponse.class);
        }
        catch (RuntimeException e)
        {
            log.warn("Discarding unreadable cached trending for key {}: {}", cacheKey, e.toString());
            return null;
        }
    }

    private static int clampLimit(Integer limit)
    {
        if (limit == null || limit <= 0)
        {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
