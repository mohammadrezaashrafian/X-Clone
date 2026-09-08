package logic_core.app.dto.request;

import logic_core.app.dto.media.UploadFile;

public record UploadMediaRequest(
        String sessionToken,
        UploadFile file
) {}