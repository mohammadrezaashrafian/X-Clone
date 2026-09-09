package Testing.passwordReset;

import logic_core.app.dto.request.RequestPasswordResetRequest;
import logic_core.app.dto.response.RequestPasswordResetResponse;
import logic_core.app.dto.validator.EmailValidator;
import logic_core.app.service.email.EmailMessage;
import logic_core.app.service.email.EmailMessageType;
import logic_core.app.service.email.EmailNotificationService;
import logic_core.app.service.passwordReset.PasswordResetOtpService;
import logic_core.app.service.ratelimit.EmailRateLimits;
import logic_core.app.usecase.auth.RequestPasswordResetUseCase;
import logic_core.common.result.Result;
import logic_core.common.security.PasswordHasher;
import logic_core.common.util.TimeProvider;
import logic_core.domain.model.UserModel;
import logic_core.domain.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RequestPasswordResetUseCase Tests")
class RequestPasswordResetUseCaseTest
{
    private PasswordResetOtpService otpService;

    @AfterEach
    void tearDown()
    {
        if (otpService != null)
        {
            otpService.close();
        }
    }

    private static TimeProvider fixedTimeProvider()
    {
        return new TimeProvider()
        {
            @Override
            public OffsetDateTime now()
            {
                return OffsetDateTime.parse("2026-07-19T10:00:00Z");
            }
        };
    }

    private PasswordResetOtpService createOtpService()
    {
        otpService = new PasswordResetOtpService(
                fixedTimeProvider(),
                new PasswordHasher(),
                Duration.ofMinutes(10),
                5,
                false
        );
        return otpService;
    }

    private EmailRateLimits defaultRateLimits()
    {
        return new EmailRateLimits(3, 15, 3, 10, 3, 15);
    }

    @Test
    @DisplayName("null request should fail")
    void nullRequest_fails()
    {
        UserRepository userRepository = mock(UserRepository.class);
        PasswordResetOtpService otp = createOtpService();
        EmailNotificationService emailService = mock(EmailNotificationService.class);
        EmailValidator emailValidator = new EmailValidator();

        RequestPasswordResetUseCase useCase = new RequestPasswordResetUseCase(
                userRepository, otp, emailService,
                (operation, subject, maxRequests, window) -> true,
                defaultRateLimits(),
                emailValidator
        );

        Result<RequestPasswordResetResponse> result = useCase.execute(null);

        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("existing user should issue OTP and schedule its delivery")
    void existingUser_issuesAndSchedulesOtp()
    {
        UserRepository userRepository = mock(UserRepository.class);
        EmailNotificationService emailService = mock(EmailNotificationService.class);
        PasswordResetOtpService otp = createOtpService();
        EmailValidator emailValidator = new EmailValidator();

        UUID userId = UUID.randomUUID();
        UserModel user = mock(UserModel.class);
        when(user.getId()).thenReturn(userId);

        // Request has mixed case + whitespace; useCase trims to "USER@EXAMPLE.COM"
        when(userRepository.findByEmail("USER@EXAMPLE.COM")).thenReturn(Optional.of(user));

        RequestPasswordResetUseCase useCase = new RequestPasswordResetUseCase(
                userRepository, otp, emailService,
                (operation, subject, maxRequests, window) -> true,
                defaultRateLimits(),
                emailValidator
        );

        Result<RequestPasswordResetResponse> result = useCase.execute(
                new RequestPasswordResetRequest("  USER@EXAMPLE.COM  ")
        );

        assertTrue(result.isSuccess());

        org.mockito.ArgumentCaptor<EmailMessage> captor =
                org.mockito.ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailService, times(1)).sendAfterCommit(captor.capture());

        EmailMessage message = captor.getValue();
        org.assertj.core.api.Assertions.assertThat(message.type())
                .isEqualTo(EmailMessageType.PASSWORD_RESET);
        org.assertj.core.api.Assertions.assertThat(message.to()).isEqualTo("USER@EXAMPLE.COM");
        // The raw code must be a 6-digit value carried ONLY to the recipient mail.
        org.assertj.core.api.Assertions.assertThat(message.variables().get("code"))
                .matches("\\d{6}");
    }

    @Test
    @DisplayName("unknown email should still return generic success and schedule nothing")
    void unknownEmail_genericSuccess_noDelivery()
    {
        UserRepository userRepository = mock(UserRepository.class);
        EmailNotificationService emailService = mock(EmailNotificationService.class);
        PasswordResetOtpService otp = createOtpService();
        EmailValidator emailValidator = new EmailValidator();

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.empty());

        RequestPasswordResetUseCase useCase = new RequestPasswordResetUseCase(
                userRepository, otp, emailService,
                (operation, subject, maxRequests, window) -> true,
                defaultRateLimits(),
                emailValidator
        );

        Result<RequestPasswordResetResponse> result = useCase.execute(
                new RequestPasswordResetRequest(" user@example.com ")
        );

        assertTrue(result.isSuccess());
        verify(emailService, never()).sendAfterCommit(any());
    }

    @Test
    @DisplayName("rate-limited request issues nothing and still returns generic success")
    void rateLimited_noDelivery_genericSuccess()
    {
        UserRepository userRepository = mock(UserRepository.class);
        EmailNotificationService emailService = mock(EmailNotificationService.class);
        PasswordResetOtpService otp = createOtpService();
        EmailValidator emailValidator = new EmailValidator();

        UUID userId = UUID.randomUUID();
        UserModel user = mock(UserModel.class);
        when(user.getId()).thenReturn(userId);
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));

        RequestPasswordResetUseCase useCase = new RequestPasswordResetUseCase(
                userRepository, otp, emailService,
                (operation, subject, maxRequests, window) -> false,
                defaultRateLimits(),
                emailValidator
        );

        Result<RequestPasswordResetResponse> result = useCase.execute(
                new RequestPasswordResetRequest("user@example.com")
        );

        assertTrue(result.isSuccess());
        verify(emailService, never()).sendAfterCommit(any());
    }

    @Test
    @DisplayName("invalid email format should fail")
    void invalidEmail_fails()
    {
        UserRepository userRepository = mock(UserRepository.class);
        EmailNotificationService emailService = mock(EmailNotificationService.class);
        PasswordResetOtpService otp = createOtpService();
        EmailValidator emailValidator = new EmailValidator();

        RequestPasswordResetUseCase useCase = new RequestPasswordResetUseCase(
                userRepository, otp, emailService,
                (operation, subject, maxRequests, window) -> true,
                defaultRateLimits(),
                emailValidator
        );

        Result<RequestPasswordResetResponse> result = useCase.execute(
                new RequestPasswordResetRequest("not-an-email")
        );

        assertFalse(result.isSuccess());
        verify(emailService, never()).sendAfterCommit(any());
        assertNotNull(otp);
    }
}