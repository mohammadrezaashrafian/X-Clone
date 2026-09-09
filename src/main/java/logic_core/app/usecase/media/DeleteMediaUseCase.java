package logic_core.app.usecase.media;

import jakarta.transaction.Transactional;
import logic_core.app.dto.request.DeleteMediaRequest;
import logic_core.app.security.AuthLockOrchestrator;
import logic_core.app.security.SessionUserContext;
import logic_core.common.exception.ForbiddenException;
import logic_core.common.exception.NotFoundException;
import logic_core.common.result.Result;
import logic_core.domain.model.MediaModel;
import logic_core.domain.model.TweetModel;
import logic_core.domain.repository.MediaRepository;
import logic_core.domain.repository.TweetRepository;
import logic_core.domain.service.MediaStorageService;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DeleteMediaUseCase
{
    @NonNull private final MediaRepository mediaRepository;
    @NonNull private final TweetRepository tweetRepository;
    @NonNull private final MediaStorageService mediaStorageService;
    @NonNull private final AuthLockOrchestrator lockOrchestrator;

    @Transactional
    public Result<Void> execute(DeleteMediaRequest request)
    {
        try
        {
            if (request == null || request.mediaId() == null)
            {
                return Result.failure(
                        "Media id is required."
                );
            }


            SessionUserContext context =
                    lockOrchestrator.lockAndGetContextByToken(
                            request.sessionToken()
                    );


            UUID currentUserId =
                    context.lockedUser().getId();



            MediaModel media =
                    mediaRepository.findById(
                                    request.mediaId()
                            )
                            .orElseThrow(() ->
                                    new NotFoundException(
                                            "Media not found."
                                    )
                            );



            // Uploaded-but-not-yet-attached media has no tweet; ownership is
            // decided directly against the media's owner instead of the tweet
            // author (a null tweetId would otherwise fail the lookup below).
            if (media.getTweetId() == null)
            {
                if (!media.isOwnedBy(currentUserId))
                {
                    throw new ForbiddenException(
                            "You cannot delete media you do not own."
                    );
                }

                mediaRepository.delete(media.getMediaId());
                mediaStorageService.delete(media.getMediaUrl());

                return Result.success(null);
            }

            TweetModel tweet =
                    tweetRepository.findById(
                                    media.getTweetId()
                            )
                            .orElseThrow(() ->
                                    new NotFoundException(
                                            "Tweet not found."
                                    )
                            );



            if (!tweet.getAuthorId().equals(currentUserId))
            {
                throw new ForbiddenException(
                        "You cannot delete media from another user's tweet."
                );
            }



            mediaRepository.delete(
                    media.getMediaId()
            );

            mediaStorageService.delete(media.getMediaUrl());

            return Result.success(null);

        }
        catch (NotFoundException |
               ForbiddenException e)
        {
            return Result.failure(
                    e.getMessage()
            );
        }
        catch (Exception e)
        {
            return Result.failure(
                    "Failed to delete media."
            );
        }
    }
}