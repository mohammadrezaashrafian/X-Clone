package logic_core.app.usecase.User;

import jakarta.transaction.Transactional;
import logic_core.app.dto.request.UpdatePasswordRequest;
import logic_core.app.dto.validator.PasswordValidator;
import logic_core.app.security.AuthLockOrchestrator;
import logic_core.common.exception.NotFoundException;
import logic_core.common.result.Result;
import logic_core.common.security.PasswordHasher;
import logic_core.domain.model.UserModel;
import logic_core.domain.repository.UserRepository;
import logic_core.session.SessionManager;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
public class UpdatePasswordUseCase
{
    @NonNull private final UserRepository repository;
    @NonNull private final AuthLockOrchestrator lockOrchestrator;
    @NonNull private final PasswordValidator passwordValidator;
    @NonNull private final SessionManager sessionManager;

    @Transactional
    public Result<Void> execute(UpdatePasswordRequest request)
    {
        try
        {
            var context = lockOrchestrator
                    .lockAndGetContextByToken(request.sessionToken());


            if(!context.lockedUser().getId().equals(request.userId()))
            {
                return Result.failure("cannot update another user's password");
            }


            passwordValidator.validate(request.newPassword());


            UserModel user = repository.findById(request.userId())
                    .orElseThrow(() ->
                            new NotFoundException("user not found")
                    );

            if(!PasswordHasher.verify(request.oldPassword(), user.getPasswordHash()))
            {
                return Result.failure("old password is incorrect");
            }

            if(PasswordHasher.verify(request.newPassword(), user.getPasswordHash()))
            {
                return Result.failure("new password cannot be same as old password");
            }

            String hashedPassword = PasswordHasher.hash(request.newPassword());

            user.setPasswordHash(hashedPassword);
            user.setUpdatedAt(OffsetDateTime.now());

            repository.update(user);

            // Stolen-token containment (Issue #21): a session minted under the
            // old credential must die with it — mirroring the existing
            // ResetPasswordUseCase behavior, which already revokes all sessions
            // for the user on a password reset.
            sessionManager.revokeAllForUser(context.lockedUser().getId());

            return Result.success(null);
        }
        catch(Exception e)
        {
            return Result.failure(e.getMessage());
        }
    }
}