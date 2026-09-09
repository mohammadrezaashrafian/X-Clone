package logic_core.domain.repository;

import logic_core.app.dto.timeline.TimelineTweet;
import logic_core.domain.model.BookmarkRelation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookmarkRepository
{
    /**
     * Whether {@code userId} has bookmarked {@code tweetId}.
     */
    boolean isBookmarked(UUID userId, UUID tweetId);

    Optional<BookmarkRelation> findBookmark(UUID userId, UUID tweetId);

    /**
     * Persist a new bookmark relationship. Duplicates are prevented by the
     * database primary key.
     */
    void saveBookmark(BookmarkRelation bookmark);

    /**
     * Remove exactly one bookmark relationship (the owner's own row for the
     * given tweet). Never touches another user's bookmark.
     */
    void deleteBookmark(BookmarkRelation bookmark);

    /**
     * Hard-deletes all bookmarks belonging to a tweet. Used during tweet
     * deletion to cascade-delete related bookmarks alongside likes, media,
     * mentions and edit history.
     */
    void deleteByTweetId(UUID tweetId);

    /**
     * The authenticated user's own bookmarked tweets, newest bookmark first,
     * in timeline shape. Only the owner's bookmarks are returned, and the
     * same visibility semantics as the timeline queries apply: soft-deleted
     * tweets are excluded, as are tweets whose author is blocked (either
     * direction) or muted by the viewer.
     */
    List<TimelineTweet> getBookmarks(UUID userId, int limit, int offset);

    /**
     * Count of the user's visible bookmarked tweets (same filters as
     * {@link #getBookmarks}).
     */
    long countBookmarks(UUID userId);
}