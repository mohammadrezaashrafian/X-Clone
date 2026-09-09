package logic_core.app.usecase.hashtag;

import logic_core.app.dto.request.SearchHashtagsRequest;
import logic_core.app.dto.response.HashtagSearchResponse;
import logic_core.app.security.AuthLockOrchestrator;
import logic_core.app.security.SessionUserContext;
import logic_core.common.exception.AppException;
import logic_core.common.exception.ValidationException;
import logic_core.common.result.Result;
import logic_core.domain.model.HashtagModel;
import logic_core.domain.repository.HashtagRepository;
import logic_core.domain.service.HashtagExtractor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Case-insensitive prefix search over persisted hashtags (HASHTAG_SEARCH).
 *
 * <p>Read-only: the search never creates or find-or-creates hashtag rows —
 * it only consumes the canonical representation persisted by the tweet
 * creation flow. The caller-supplied query is normalized to the canonical
 * tag form (trim, strip a leading {@code #}, lowercase, validate) via the
 * shared {@link HashtagExtractor#normalize(String)} used by follow/feed, so
 * {@code #Jav} resolves to the prefix {@code jav} and matches persisted
 * {@code java}.
 *
 * <p>The actor is derived from the authenticated session token, never from a
 * caller-supplied field. Pagination follows the modern V2.1 convention:
 * zero-based page, page size clamped to 1..100 (default 20),
 * {@code hasNext = offset + items.size() < totalItems}.
 */
@Service
@RequiredArgsConstructor
public class SearchHashtagsUseCase
{
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    @NonNull private final HashtagRepository hashtagRepository;
    @NonNull private final AuthLockOrchestrator lockOrchestrator;

    public Result<HashtagSearchResponse> execute(SearchHashtagsRequest request)
    {
        try
        {
            SessionUserContext context =
                    lockOrchestrator.lockAndGetContextByToken(
                            request.sessionToken()
                    );

            // Hashtags are globally visible; the lock above is the
            // authentication gate. No actor-scoped filtering applies.

            if (request.query() == null || request.query().isBlank())
            {
                throw new ValidationException("Search query is required.");
            }

            // Canonical form of the caller-supplied prefix: trim, strip the
            // leading '#', lowercase, validate against the tag charset. This
            // is the same normalization the persisted tags carry.
            String prefix = HashtagExtractor.normalize(request.query());

            int page = Math.max(request.page(), 0);
            int pageSize = clampPageSize(request.pageSize());
            int offset = page * pageSize;

            List<HashtagModel> matches =
                    hashtagRepository.searchByTagPrefix(prefix, pageSize, offset);

            long totalItems = hashtagRepository.countByTagPrefix(prefix);

            boolean hasNext = offset + matches.size() < totalItems;

            List<HashtagSearchResponse.HashtagSearchItem> items = matches.stream()
                    .map(hashtag -> new HashtagSearchResponse.HashtagSearchItem(
                            hashtag.getTag()))
                    .toList();

            return Result.success(
                    new HashtagSearchResponse(items, totalItems, page, pageSize, hasNext)
            );
        }
        catch (AppException e)
        {
            return Result.failure(e.getMessage());
        }
    }

    private static int clampPageSize(int pageSize)
    {
        if (pageSize <= 0)
        {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }
}
