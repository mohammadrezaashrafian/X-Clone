package logic_core.app.usecase.media;

import logic_core.app.dto.media.UploadFile;
import logic_core.app.dto.request.UploadMediaRequest;
import logic_core.app.dto.response.UploadMediaResponse;
import logic_core.app.dto.validator.MediaValidator;
import logic_core.app.security.AuthLockOrchestrator;
import logic_core.app.security.SessionUserContext;
import logic_core.common.exception.ValidationException;
import logic_core.common.result.Result;
import logic_core.domain.model.MediaModel;
import logic_core.domain.model.media.MediaType;
import logic_core.domain.repository.MediaRepository;
import logic_core.domain.service.MediaStorageService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Uploads a tweet attachment (image/video/GIF).
 *
 * <p>Stores the file through {@link MediaStorageService} and persists an
 * unattached {@link MediaModel} owned by the authenticated actor. The returned
 * media id is the stable token a client subsequently supplies to
 * {@code TWEET_CREATE}/{@code TWEET_REPLY} to attach the media; ownership is
 * enforced again at attachment time.
 */
@Service
@RequiredArgsConstructor
public class UploadMediaUseCase
{
    @NonNull private final AuthLockOrchestrator lockOrchestrator;
    @NonNull private final MediaValidator mediaValidator;
    @NonNull private final MediaStorageService mediaStorageService;
    @NonNull private final MediaRepository mediaRepository;

    @Transactional
    public Result<UploadMediaResponse> execute(UploadMediaRequest request)
    {
        if (request == null)
        {
            return Result.failure("Upload request cannot be null.");
        }

        try
        {
            UploadFile file = request.file();

            mediaValidator.validateUpload(file);

            SessionUserContext context =
                    lockOrchestrator.lockAndGetContextByToken(request.sessionToken());

            UUID ownerId = context.lockedUser().getId();

            String storedUrl = null;
            try
            {
                MediaType mediaType = mediaValidator.resolveMediaType(file);

                // Derive the storage extension from the validated media type —
                // the client-supplied filename extension is never trusted.
                String storageExtension =
                        mediaValidator.resolveStorageExtension(file);

                storedUrl = mediaStorageService.uploadTweetMedia(
                        file, storageExtension);

                // displayOrder reflects the user's upload sequence (their
                // existing unattached media count) so that attaching several
                // uploaded media later preserves upload order.
                short displayOrder =
                        (short) mediaRepository.countUnattachedByUser(ownerId);

                MediaModel media = MediaModel.builder()
                        .tweetId(null)
                        .mediaUrl(storedUrl)
                        .originalFilename(file.fileName())
                        .fileSizeBytes((long) file.data().length)
                        .mediaType(mediaType)
                        .displayOrder(displayOrder)
                        .uploadedBy(ownerId)
                        .build();

                MediaModel saved = mediaRepository.upload(media);

                return Result.success(new UploadMediaResponse(
                        saved.getMediaId(),
                        saved.getMediaUrl(),
                        saved.getOriginalFilename(),
                        saved.getFileSizeBytes(),
                        saved.getMediaType()
                ));
            }
            catch (Exception e)
            {
                // If storage succeeded but persistence failed, remove the
                // orphaned file so we don't leak unreferenced bytes.
                if (storedUrl != null)
                {
                    try
                    {
                        mediaStorageService.delete(storedUrl);
                    }
                    catch (Exception cleanupFailure)
                    {
                        cleanupFailure.printStackTrace();
                    }
                }
                throw e;
            }
        }
        catch (ValidationException e)
        {
            return Result.failure(e.getMessage());
        }
        catch (Exception e)
        {
            return Result.failure("Failed to upload media.");
        }
    }
}