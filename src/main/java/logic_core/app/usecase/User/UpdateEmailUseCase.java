package logic_core.app.usecase.User;

import jakarta.transaction.Transactional;
import logic_core.app.cache.CacheInvalidation;
import logic_core.app.cache.CacheKeys;
import logic_core.app.dto.request.UpdateEmailRequest;
import logic_core.app.dto.validator.EmailValidator;
import logic_core.app.security.AuthLockOrchestrator;
import logic_core.app.security.SessionUserContext;
import logic_core.common.exception.NotFoundException;
import logic_core.common.result.Result;
import logic_core.domain.model.UserModel;
import logic_core.domain.repository.UserRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
public class UpdateEmailUseCase
{
    @NonNull private final UserRepository repository;
    @NonNull private final AuthLockOrchestrator lockOrchestrator;
    @NonNull private final EmailValidator emailValidator;
    @NonNull private final CacheInvalidation cacheInvalidation;

    @Transactional
    public Result<Void> execute(UpdateEmailRequest request)
    {
        try
        {
            SessionUserContext context = lockOrchestrator.lockAndGetContextByToken(request.sessionToken());

            if(!context.lockedUser().getId().equals(request.userId()))
            {
                return Result.failure("cannot update another user's email");
            }

            emailValidator.validate(request.email());


            UserModel user = repository.findById(request.userId())
                    .orElseThrow(() -> new NotFoundException("user not found"));

            String newEmail = request.email().trim();

            if(newEmail.equalsIgnoreCase(user.getEmail()))
            {
                return Result.failure("new email is same as current email");
            }

            if(repository.existsByEmail(newEmail))
            {
                return Result.failure("email already exists");
            }

            user.setEmail(newEmail);
            user.setUpdatedAt(OffsetDateTime.now());

            repository.update(user);

            cacheInvalidation.evictAfterCommit(CacheKeys.userProfile(user.getId()));

            return Result.success(null);
        }
        catch(Exception e)
        {
            return Result.failure(e.getMessage());
        }
    }
}