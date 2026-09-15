package logic_core.app.usecase.auth;

import logic_core.app.dto.request.RegisterRequest;
import logic_core.app.dto.response.AuthResponse;
import logic_core.app.dto.validator.RegisterValidator;
import logic_core.app.mapper.AuthMapper;
import logic_core.app.service.email.EmailMessage;
import logic_core.app.service.email.EmailMessageType;
import logic_core.app.service.email.EmailNotificationService;
import logic_core.app.service.passwordReset.PasswordResetOtpService;
import logic_core.common.exception.ConflictException;
import logic_core.common.exception.ForbiddenException;
import logic_core.common.exception.NotFoundException;
import logic_core.common.exception.ValidationException;
import logic_core.common.result.Result;
import logic_core.common.security.PasswordHasher;
import logic_core.common.util.TimeProvider;
import logic_core.domain.model.SessionModel;
import logic_core.domain.model.UserModel;
import logic_core.domain.policy.RegistrationPolicy;
import logic_core.domain.repository.UserRepository;
import logic_core.session.SessionManager;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class RegisterUserUseCase
{
    private static final Logger log = LoggerFactory.getLogger(RegisterUserUseCase.class);

    @NonNull private final RegisterValidator validator;
    @NonNull private final RegistrationPolicy policy;
    @NonNull private final UserRepository userRepository;
    @NonNull private final PasswordHasher passwordHasher;
    @NonNull private final TimeProvider timeProvider;
    @NonNull private final SessionManager sessionManager;
    @NonNull private final PasswordResetOtpService otpService;
    @NonNull private final EmailNotificationService emailService;

    @Transactional
    public Result<AuthResponse> execute(RegisterRequest request)
    {
        try
        {
            validator.validate(
                    request.username(),
                    request.password(),
                    request.email()
            );

            policy.validate(
                    request.username(),
                    request.email()
            );

            UserModel newUser = createUserModel(request);

            UserModel persistedUser = userRepository.save(newUser)
                    .orElseThrow(
                            () -> new RuntimeException(
                                    "Failed to persist user."
                            )
                    );

            SessionModel session = sessionManager.startSession(persistedUser.getId());

            // Generate email verification OTP and send asynchronously after commit.
            try
            {
                String rawOtp = otpService.issue(request.email().trim().toLowerCase(), persistedUser.getId());
                emailService.sendAfterCommit(new EmailMessage(
                        request.email().trim(),
                        EmailMessageType.EMAIL_VERIFICATION,
                        Map.of(
                                "code", rawOtp,
                                "email", request.email().trim(),
                                "appName", "X-Clone",
                                "expiresInMinutes", String.valueOf(
                                        PasswordResetOtpService.DEFAULT_TTL.toMinutes())
                        )
                ));
            }
            catch (Exception e)
            {
                // Email delivery failure must not block registration.
                log.warn("Failed to send verification email after registration for user={}: {}",
                        persistedUser.getUsername(), e.getMessage());
            }

            return Result.success(AuthMapper.toResponse(persistedUser, session));
        }
        catch (ValidationException | ForbiddenException | ConflictException | NotFoundException e)
        {
            return Result.failure(e.getMessage());
        }
    }

    private UserModel createUserModel(RegisterRequest request)
    {
        String passwordHash = passwordHasher.hash(request.password());
        return UserModel.createNew(
                null,
                request.username(),
                request.displayName(),
                request.email(),
                passwordHash,
                timeProvider.now()
        );
    }
}
