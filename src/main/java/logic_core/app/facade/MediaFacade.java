package logic_core.app.facade;

import logic_core.app.dto.request.DeleteMediaRequest;
import logic_core.app.dto.request.DownloadMediaRequest;
import logic_core.app.dto.request.UploadMediaRequest;
import logic_core.app.dto.response.DownloadMediaResponse;
import logic_core.app.dto.response.UploadMediaResponse;
import logic_core.app.usecase.media.DeleteMediaUseCase;
import logic_core.app.usecase.media.DownloadMediaUseCase;
import logic_core.app.usecase.media.UploadMediaUseCase;
import logic_core.common.result.Result;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MediaFacade
{
    private final DeleteMediaUseCase deleteMediaUseCase;
    private final DownloadMediaUseCase downloadMediaUseCase;
    private final UploadMediaUseCase uploadMediaUseCase;

    public Result<Void> deleteMedia(DeleteMediaRequest request)
    {
        return deleteMediaUseCase.execute(request);
    }

    public Result<DownloadMediaResponse> downloadMedia(DownloadMediaRequest request)
    {
        return downloadMediaUseCase.execute(request);
    }

    public Result<UploadMediaResponse> uploadMedia(UploadMediaRequest request)
    {
        return uploadMediaUseCase.execute(request);
    }

}
