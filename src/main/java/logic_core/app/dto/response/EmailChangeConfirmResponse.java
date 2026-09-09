package logic_core.app.dto.response;

public record EmailChangeConfirmResponse(
        boolean changed,
        String message,
        String email
) {}