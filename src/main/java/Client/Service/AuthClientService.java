package Client.Service;

import Client.ClientApplicationContext;
import Client.session.ClientSession;
import Client.transport.SocketClient;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import logic_core.app.dto.request.*;
import logic_core.app.dto.response.*;
import logic_core.common.exception.AuthApiException;
import logic_core.infrastructure.transport.RequestEnvelope;
import logic_core.infrastructure.transport.RequestType;
import logic_core.infrastructure.transport.ResponseEnvelope;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public final class AuthClientService
{
    private final SocketClient socketClient;
    private final ClientSession session;
    private final ExecutorService networkExecutor;
    private final Gson gson;

    public AuthClientService(ClientApplicationContext context)
    {
        this(
                Objects.requireNonNull(context, "context"),
                context.socketClient(),
                context.session(),
                context.networkExecutor(),
                new Gson().newBuilder().serializeNulls().create()
        );
    }

    public AuthClientService(
            ClientApplicationContext context,
            SocketClient socketClient,
            ClientSession session,
            ExecutorService networkExecutor,
            Gson gson
    )
    {
        Objects.requireNonNull(context, "context");
        this.socketClient = Objects.requireNonNull(socketClient, "socketClient");
        this.session = Objects.requireNonNull(session, "session");
        this.networkExecutor = Objects.requireNonNull(networkExecutor, "networkExecutor");
        this.gson = Objects.requireNonNull(gson, "gson");
    }

    public CompletableFuture<AuthResult<AuthResponse>> login(String username, String password)
    {
        LoginRequest request = new LoginRequest(username, password);
        return execute(RequestType.AUTH_LOGIN, request, AuthResponse.class, true, false);
    }

    public CompletableFuture<AuthResult<AuthResponse>> register(
            String username,
            String email,
            String password,
            String displayName
    )
    {
        RegisterRequest request = new RegisterRequest(username, email, password, displayName);
        return execute(RequestType.AUTH_REGISTER, request, AuthResponse.class, true, false);
    }

    public CompletableFuture<AuthResult<LogoutResponse>> logout()
    {
        if (!session.isLoggedIn() || session.getToken() == null || session.getCurrentUserId() == null)
        {
            return CompletableFuture.completedFuture(
                    AuthResult.failure("NOT_LOGGED_IN", "No active session exists.")
            );
        }

        LogoutRequest request = new LogoutRequest(session.getToken(), session.getCurrentUserId());
        return execute(RequestType.AUTH_LOGOUT, request, LogoutResponse.class, false, true);
    }

    public CompletableFuture<AuthResult<AuthResponse>> refresh(String refreshToken)
    {
        if (refreshToken == null || refreshToken.isBlank())
        {
            return CompletableFuture.completedFuture(
                    AuthResult.failure("MISSING_TOKEN", "Refresh token is required.")
            );
        }

        RefreshSessionRequest request = new RefreshSessionRequest(refreshToken);
        return execute(RequestType.AUTH_REFRESH, request, AuthResponse.class, true, false);
    }

    public CompletableFuture<AuthResult<RequestPasswordResetResponse>> requestPasswordReset(String email)
    {
        RequestPasswordResetRequest request = new RequestPasswordResetRequest(email);
        return execute(
                RequestType.AUTH_REQUEST_PASSWORD_RESET,
                request,
                RequestPasswordResetResponse.class,
                false,
                false
        );
    }

    public CompletableFuture<AuthResult<VerifyPasswordResetCodeResponse>> verifyPasswordResetCode(String email, String code)
    {
        VerifyPasswordResetCodeRequest request = new VerifyPasswordResetCodeRequest(email, code);
        return execute(
                RequestType.AUTH_VERIFY_PASSWORD_RESET_CODE,
                request,
                VerifyPasswordResetCodeResponse.class,
                false,
                false
        );
    }

    public CompletableFuture<AuthResult<ResetPasswordResponse>> resetPassword(String email, String code, String newPassword)
    {
        ResetPasswordRequest request = new ResetPasswordRequest(email, code, newPassword);
        return execute(
                RequestType.AUTH_RESET_PASSWORD,
                request,
                ResetPasswordResponse.class,
                false,
                false
        );
    }

    public CompletableFuture<AuthResult<EmailVerificationConfirmResponse>> confirmEmailVerification(String code)
    {
        ConfirmEmailVerificationRequest request =
                new ConfirmEmailVerificationRequest(session.getToken(), code);
        return execute(
                RequestType.EMAIL_VERIFY_CONFIRM,
                request,
                EmailVerificationConfirmResponse.class,
                false,
                false
        );
    }

    public CompletableFuture<AuthResult<EmailVerificationRequestResponse>> requestEmailVerification()
    {
        RequestEmailVerificationRequest request =
                new RequestEmailVerificationRequest(session.getToken());
        return execute(
                RequestType.EMAIL_VERIFY_REQUEST,
                request,
                EmailVerificationRequestResponse.class,
                false,
                false
        );
    }


    private <T> CompletableFuture<AuthResult<T>> execute(
            RequestType requestType,
            Object requestBody,
            Class<T> responseClass,
            boolean updateSessionOnSuccess,
            boolean clearSessionOnSuccess
    )
    {
        return CompletableFuture.supplyAsync(() -> {
            try
            {
                socketClient.connect();

                JsonElement payload = gson.toJsonTree(requestBody);
                RequestEnvelope request = new RequestEnvelope(UUID.randomUUID(), requestType, payload, session.getToken());
                ResponseEnvelope response = socketClient.send(request);

                if (response == null)
                {
                    return AuthResult.failure("EMPTY_RESPONSE", "Server returned no response.");
                }

                if (!response.isSuccess())
                {
                    String errorCode = normalizeErrorCode(response.getError());
                    String errorMessage = normalizeErrorMessage(response.errorMessage());
                    maybeClearSessionOnFailure(errorCode);
                    return AuthResult.failure(errorCode, errorMessage);
                }

                if (response.getData() == null || response.getData().isJsonNull())
                {
                    if (responseClass == LogoutResponse.class)
                    {
                        if (clearSessionOnSuccess)
                        {
                            session.clear();
                        }
                        return AuthResult.success(null);
                    }
                    return AuthResult.failure("INVALID_PAYLOAD", "Response payload is missing.");
                }

                T data = gson.fromJson(response.getData(), responseClass);
                if (data == null)
                {
                    return AuthResult.failure("INVALID_PAYLOAD", "Unable to parse response payload.");
                }

                if (updateSessionOnSuccess)
                {
                    applySessionFromAuthResponse(data);
                }

                if (clearSessionOnSuccess)
                {
                    session.clear();
                }

                return AuthResult.success(data);
            }
            catch (AuthApiException ex)
            {
                maybeClearSessionOnFailure(ex.getErrorCode());
                return AuthResult.failure(ex.getErrorCode(), ex.getMessage());
            }
            catch (Exception ex)
            {
                maybeClearSessionOnFailure("NETWORK_ERROR");
                return AuthResult.failure("NETWORK_ERROR", ex.getMessage());
            }
        }, networkExecutor);
    }

    private void applySessionFromAuthResponse(Object data)
    {
        if (!(data instanceof AuthResponse authResponse))
        {
            return;
        }

        if (authResponse.token() == null || authResponse.token().isBlank() || authResponse.userId() == null)
        {
            throw new AuthApiException("INVALID_AUTH_RESPONSE", "Authentication response is missing token or user id.");
        }

        session.updateSession(authResponse.token(), authResponse.userId(), authResponse.username());
    }

    private void maybeClearSessionOnFailure(String errorCode)
    {
        if (errorCode == null)
        {
            return;
        }

        switch (errorCode)
        {
            case "AUTH_REFRESH_FAILED",
                 "AUTH_LOGOUT_FAILED",
                 "AUTH_SESSION_EXPIRED",
                 "AUTH_TOKEN_INVALID",
                 "AUTH_TOKEN_EXPIRED",
                 "INVALID_SESSION",
                 "MISSING_TOKEN",
                 "UNAUTHORIZED",
                 "FORBIDDEN" -> session.clear();
            default -> {
            }
        }
    }

    private String normalizeErrorCode(String code)
    {
        if (code == null || code.isBlank())
        {
            return "UNKNOWN_ERROR";
        }
        return code.trim();
    }

    private String normalizeErrorMessage(String message)
    {
        if (message == null || message.isBlank())
        {
            return "Unknown error occurred.";
        }
        return message.trim();
    }

    public record AuthResult<T>(boolean success, T data, String errorCode, String errorMessage)
    {
        public static <T> AuthResult<T> success(T data)
        {
            return new AuthResult<>(true, data, null, null);
        }

        public static <T> AuthResult<T> failure(String errorCode, String errorMessage)
        {
            return new AuthResult<>(false, null, errorCode, errorMessage);
        }

        public boolean isSuccess()
        {
            return success;
        }
    }
}