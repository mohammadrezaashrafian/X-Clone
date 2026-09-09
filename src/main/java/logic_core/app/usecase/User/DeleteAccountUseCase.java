package logic_core.app.usecase.User;

import jakarta.transaction.Transactional;
import logic_core.app.cache.CacheInvalidation;
import logic_core.app.cache.CacheKeys;
import logic_core.app.dto.request.DeleteAccountRequest;
import logic_core.app.security.AuthLockOrchestrator;
import logic_core.app.security.SessionUserContext;
import logic_core.common.exception.NotFoundException;
import logic_core.common.result.Result;
import logic_core.common.security.PasswordHasher;
import logic_core.common.util.TimeProvider;
import logic_core.domain.model.UserModel;
import logic_core.domain.repository.UserRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DeleteAccountUseCase
{
    @NonNull private final UserRepository repository;
    @NonNull private final AuthLockOrchestrator lockOrchestrator;
    @NonNull private final TimeProvider timeProvider;
    @NonNull private final CacheInvalidation cacheInvalidation;

    @Transactional
    public Result<Void> execute(DeleteAccountRequest request)
    {
        try
        {
            SessionUserContext context = lockOrchestrator.lockAndGetContextByToken(request.sessionToken());


            if(!context.lockedUser().getId().equals(request.useId()))
            {
                return Result.failure("cannot delete another user's account");
            }


            UserModel user = repository.findById(request.useId())
                    .orElseThrow(() -> new NotFoundException("user not found"));


            if(!PasswordHasher.verify(request.password(), user.getPasswordHash()))
            {
                return Result.failure(
                        "password is incorrect"
                );
            }

            user.setDeleted(true);

            user.setUpdatedAt(timeProvider.now());

            repository.update(user);

            cacheInvalidation.evictAfterCommit(CacheKeys.userProfile(user.getId()));

            return Result.success(null);
        }
        catch(Exception e)
        {
            e.printStackTrace();
            return Result.failure(e.getMessage());
        }
    }
}