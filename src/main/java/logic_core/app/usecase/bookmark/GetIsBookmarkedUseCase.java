package logic_core.app.usecase.bookmark;

import jakarta.transaction.Transactional;
import logic_core.app.dto.request.GetIsBookmarkedRequest;
import logic_core.app.dto.response.GetIsBookmarkedResponse;
import logic_core.app.security.AuthLockOrchestrator;
import logic_core.app.security.SessionUserContext;
import logic_core.common.result.Result;
import logic_core.domain.model.UserModel;
import logic_core.domain.repository.BookmarkRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GetIsBookmarkedUseCase
{
    @NonNull private final BookmarkRepository bookmarkRepository;
    @NonNull private final AuthLockOrchestrator lockOrchestrator;

    @Transactional
    public Result<GetIsBookmarkedResponse> execute(GetIsBookmarkedRequest request)
    {
        try
        {
            SessionUserContext context =
                    lockOrchestrator.lockAndGetContextByToken(
                            request.sessionToken()
                    );

            UserModel currentUser = context.lockedUser();

            boolean bookmarked = bookmarkRepository.isBookmarked(
                    currentUser.getId(),
                    request.tweetId()
            );

            return Result.success(
                    GetIsBookmarkedResponse.builder()
                            .bookmarked(bookmarked)
                            .build()
            );
        }
        catch (Exception e)
        {
            return Result.failure(e.getMessage());
        }
    }
}