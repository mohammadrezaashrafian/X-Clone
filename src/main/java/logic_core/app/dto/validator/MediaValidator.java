package logic_core.app.dto.validator;

import logic_core.app.dto.media.UploadFile;
import logic_core.common.exception.ValidationException;
import logic_core.domain.model.media.MediaType;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Validation for tweet-media uploads (V2.1 #6).
 *
 * <p>Reuses the project's validator conventions (a {@code @Component} that
 * throws {@link ValidationException} on invalid input). Supports image, video
 * and GIF uploads and enforces a per-file size cap consistent with the other
 * upload validators in {@link UserValidator}.
 */
@Component
public class MediaValidator
{
    private static final long MAX_MEDIA_FILE_BYTES = 15L * 1024 * 1024;

    /**
     * Allowlisted content types and the canonical storage extension each maps
     * to. The client-supplied filename extension is never trusted; the stored
     * file's extension is always derived from the validated content type, so
     * an upload cannot be stored under an arbitrary/dangerous extension
     * (e.g. {@code .html}, {@code .svg}) regardless of its filename.
     */
    private static final Map<String, String> CONTENT_TYPE_EXTENSIONS = Map.ofEntries(
            Map.entry("image/jpeg", ".jpg"),
            Map.entry("image/jpg", ".jpg"),
            Map.entry("image/png", ".png"),
            Map.entry("image/webp", ".webp"),
            Map.entry("image/gif", ".gif"),
            Map.entry("video/mp4", ".mp4"),
            Map.entry("video/webm", ".webm"),
            Map.entry("video/quicktime", ".mov"));

    /**
     * Returns the canonical storage extension for the upload's content type.
     * Throws {@link ValidationException} when the content type is not
     * supported for storage, even if it belongs to an allowed family —
     * only the explicitly allowlisted types may be persisted.
     */
    public String resolveStorageExtension(UploadFile file)
    {
        String extension = file == null || file.contentType() == null
                ? null
                : CONTENT_TYPE_EXTENSIONS.get(
                        file.contentType().toLowerCase().trim());

        if (extension == null)
        {
            throw new ValidationException("media.type.unsupported");
        }
        return extension;
    }

    public void validateUpload(UploadFile file)
    {
        if (file == null)
        {
            throw new ValidationException("media.file.required");
        }
        if (file.data() == null || file.data().length == 0)
        {
            throw new ValidationException("media.file.empty");
        }
        if (file.contentType() == null || file.contentType().isBlank())
        {
            throw new ValidationException("media.contentType.required");
        }
        if (file.data().length > MAX_MEDIA_FILE_BYTES)
        {
            throw new ValidationException("media.file.too.large");
        }
        if (resolveMediaType(file) == null)
        {
            throw new ValidationException("media.type.unsupported");
        }
        // Only explicitly allowlisted content types may be stored; this also
        // rejects dangerous types inside allowed families (e.g. image/svg+xml).
        resolveStorageExtension(file);
    }

    /**
     * Resolves the supported {@link MediaType} from the upload content type,
     * or {@code null} when the type is not supported.
     */
    public MediaType resolveMediaType(UploadFile file)
    {
        if (file == null || file.contentType() == null)
        {
            return null;
        }

        String type = file.contentType().toLowerCase();

        if (type.equals("image/gif"))
        {
            return MediaType.GIF;
        }
        if (type.startsWith("image/"))
        {
            return MediaType.IMAGE;
        }
        if (type.startsWith("video/"))
        {
            return MediaType.VIDEO;
        }
        return null;
    }
}