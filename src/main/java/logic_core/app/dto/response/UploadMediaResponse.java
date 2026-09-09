package logic_core.app.dto.response;

import logic_core.domain.model.media.MediaType;

import java.util.UUID;

public record UploadMediaResponse(
        UUID mediaId,
        String mediaUrl,
        String originalFilename,
        Long fileSizeBytes,
        MediaType mediaType
) {}