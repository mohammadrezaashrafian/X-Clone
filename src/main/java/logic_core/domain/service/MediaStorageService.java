package logic_core.domain.service;

import logic_core.app.dto.media.UploadFile;

import java.io.File;

public interface MediaStorageService
{

    String uploadAvatar(UploadFile file);

    String uploadBanner(
            UploadFile file
    );

    /**
     * Stores a tweet attachment (image/video/GIF) and returns the served URL.
     * The stored file's extension is {@code storageExtension}, derived from
     * the upload's validated content type by the caller — the client-supplied
     * filename extension is never used.
     */
    String uploadTweetMedia(UploadFile file, String storageExtension);

    void delete(String path);

}