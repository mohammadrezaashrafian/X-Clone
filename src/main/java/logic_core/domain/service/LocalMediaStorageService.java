package logic_core.domain.service;

import logic_core.app.dto.media.UploadFile;
import logic_core.domain.service.MediaStorageService;
import logic_core.infrastructure.media.MediaProperties;
import lombok.RequiredArgsConstructor;


import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

@RequiredArgsConstructor
public class LocalMediaStorageService implements MediaStorageService
{
    private final MediaProperties properties;

    @Override
    public String uploadAvatar(UploadFile file)
    {
        try
        {
            String extension = getExtension(file.fileName());

            String newFileName = UUID.randomUUID() + extension;

            Path avatarDirectory = Paths.get(properties.getRootPath(), "avatars");

            if(!Files.exists(avatarDirectory))
            {
                Files.createDirectories(avatarDirectory);
            }


            Path filePath = avatarDirectory.resolve(newFileName);

            Files.write(filePath, file.data(), StandardOpenOption.CREATE_NEW);


            return "/media/avatars/" + newFileName;

        }
        catch(IOException e)
        {
            throw new RuntimeException("Failed to store avatar", e);
        }
    }

    @Override
    public String uploadTweetMedia(UploadFile file, String storageExtension)
    {
        try
        {
            // Never trust the client-supplied extension: the stored file's
            // extension is derived from the upload's validated content type.
            String newFileName = UUID.randomUUID() + storageExtension;

            Path tweetMediaDirectory = Paths.get(properties.getRootPath(), "tweets");

            if (!Files.exists(tweetMediaDirectory))
            {
                Files.createDirectories(tweetMediaDirectory);
            }

            Path filePath = tweetMediaDirectory.resolve(newFileName);
            Files.write(filePath, file.data(), StandardOpenOption.CREATE_NEW);

            return "/media/tweets/" + newFileName;
        }
        catch (IOException e)
        {
            throw new RuntimeException("Failed to store tweet media", e);
        }
    }

    @Override
    public void delete(String path)
    {

        try
        {

            if(path == null)
                return;

            String fileName = Paths.get(path)
                            .getFileName()
                            .toString();

            String[] segments = path.split("/");
            String subdir = segments.length > 2 ? segments[2] : "avatars";

            Path file = Paths.get(properties.getRootPath(), subdir, fileName);

            Files.deleteIfExists(file);

        }
        catch(IOException e)
        {
            throw new RuntimeException("Failed to delete media", e);
        }

    }



    private String getExtension(String fileName)
    {

        int index = fileName.lastIndexOf(".");

        if(index == -1)
            return ".jpg";


        return fileName.substring(index);
    }
    @Override
    public String uploadBanner(UploadFile file)
    {
        try
        {
            String extension = getExtension(file.fileName());


            String newFileName = UUID.randomUUID() + extension;

            Path coverDirectory = Paths.get(properties.getRootPath(), "covers");


            if (!Files.exists(coverDirectory))
            {
                Files.createDirectories(coverDirectory);
            }

            Path filePath = coverDirectory.resolve(newFileName);
            Files.write(filePath, file.data(), StandardOpenOption.CREATE_NEW);

            return "/media/covers/" + newFileName;

        }
        catch (IOException e)
        {
            throw new RuntimeException("Failed to store cover", e);
        }
    }


}