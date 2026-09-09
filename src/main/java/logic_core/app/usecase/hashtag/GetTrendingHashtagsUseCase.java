package logic_core.app.usecase.hashtag;

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
    /** The one explicitly supported trending window, in hours. */
    static final int WINDOW_HOURS = 24;

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;

    @NonNull private final HashtagRepository hashtagRepository;
    @NonNull private final AuthLockOrchestrator lockOrchestrator;
    @NonNull private final TimeProvider timeProvider;

    public Result<TrendingHashtagsResponse> execute(GetTrendingHashtagsRequest request)
    {
        try
        {
            lockOrchestrator.lockAndGetContextByToken(request.sessionToken());

            // One clock snapshot defines the window for both the rows query
            // and the count query, so the response can never be internally
            // inconsistent.
            OffsetDateTime windowEnd = timeProvider.now();
            OffsetDateTime windowStart = windowEnd.minusHours(WINDOW_HOURS);

            int limit = clampLimit(request.limit());

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

            return Result.success(
                    TrendingHashtagsResponse.builder()
                            .items(items)
                            .totalItems(totalItems)
                            .windowDescription("last_24_hours_[start,end)")
                            .build()
            );
        }
        catch (AppException e)
        {
            return Result.failure(e.getMessage());
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
