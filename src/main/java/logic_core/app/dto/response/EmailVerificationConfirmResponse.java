package logic_core.app.dto.response;

public record EmailVerificationConfirmResponse(
        boolean verified,
        String message
) {}