package logic_core.app.dto.request;

import java.util.UUID;

/**
 * Request for retrieving a single active tweet by id (TWEET_GET).
 *
 * <p>The authenticated actor is derived from {@code token} server-side; the
 * actor is never taken from a caller-supplied field.
 */
public record GetTweetRequest(
        UUID tweetId,
        String token
) {
}
