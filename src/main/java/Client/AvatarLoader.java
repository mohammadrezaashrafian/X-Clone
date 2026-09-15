package Client;

import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import java.io.File;
import java.net.URL;
import java.util.logging.Logger;

/**
 * Shared utility for loading avatars consistently across the application.
 * Centralizes file path resolution, default avatar fallback, and image creation.
 */
public final class AvatarLoader {

    private static final Logger log = Logger.getLogger(AvatarLoader.class.getName());
    private static final String DEFAULT_AVATAR_RESOURCE = "/Client/images/user (1).png";

    private AvatarLoader() {
        // Utility class, no instantiation
    }

    /**
     * Loads an avatar into the given ImageView from a URL or file path.
     * Falls back to the default avatar if the URL is null, blank, or the file doesn't exist.
     *
     * @param imageView the ImageView to load the avatar into
     * @param avatarUrl the avatar URL or file path (can be null or blank)
     */
    public static void loadAvatar(ImageView imageView, String avatarUrl) {
        if (imageView == null) {
            return;
        }

        if (avatarUrl != null && !avatarUrl.isBlank()) {
            try {
                String cleanPath = avatarUrl.startsWith("/") || avatarUrl.startsWith("\\")
                        ? avatarUrl.substring(1)
                        : avatarUrl;

                // Try the project-relative "data/<path>" form first, then the
                // working-directory form, and finally treat the value as a direct
                // file path if it points to an existing file on its own.
                File avatarFile = resolveAvatarFile(cleanPath);
                if (avatarFile != null && avatarFile.exists()) {
                    imageView.setImage(new Image(avatarFile.toURI().toString(), true));
                    return;
                }
            } catch (Exception e) {
                log.warning("Failed to load avatar: " + e.getMessage());
            }
        }

        loadDefaultAvatar(imageView);
    }

    /**
     * Attempts to resolve an avatar path against the common storage locations
     * used by this client.
     *
     * @param cleanPath avatar path without leading separators
     * @return the first existing file found, or {@code null}
     */
    private static File resolveAvatarFile(String cleanPath) {
        if (cleanPath == null || cleanPath.isBlank()) {
            return null;
        }

        File dataRoot = new File("data");
        File candidate = new File(dataRoot, cleanPath);
        if (candidate.exists()) {
            return candidate;
        }

        String userDir = System.getProperty("user.dir");
        if (userDir != null) {
            File userDirData = new File(userDir, "data");
            File userDirCandidate = new File(userDirData, cleanPath);
            if (userDirCandidate.exists()) {
                return userDirCandidate;
            }
        }

        // If the value already points at an existing file, honor it directly.
        File direct = new File(cleanPath);
        if (direct.exists()) {
            return direct;
        }

        return null;
    }

    /**
     * Loads the default avatar into the given ImageView.
     *
     * @param imageView the ImageView to load the default avatar into
     */
    public static void loadDefaultAvatar(ImageView imageView) {
        if (imageView == null) {
            return;
        }

        try {
            URL resource = AvatarLoader.class.getResource(DEFAULT_AVATAR_RESOURCE);
            if (resource != null) {
                imageView.setImage(new Image(resource.toExternalForm(), true));
            } else {
                imageView.setImage(null);
                log.warning("Default avatar resource not found at: " + DEFAULT_AVATAR_RESOURCE);
            }
        } catch (Exception e) {
            imageView.setImage(null);
            log.warning("Failed to load default avatar: " + e.getMessage());
        }
    }
}
