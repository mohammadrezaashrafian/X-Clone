-- ============================================================================
-- V2 — Bookmarks (V2.1 #3 Bookmark System)
--
-- Bookmarks are private user-owned relationships between a user and a tweet.
-- They are never publicly visible; the bookmark list endpoint only ever
-- returns the authenticated user's own bookmarks.
--
-- The table mirrors the likes relationship shape (composite key on
-- (user_id, tweet_id), created_at timestamp, CASCADE deletes from both
-- users and tweets) so a bookmark can never outlive its user or its tweet.
-- ============================================================================

CREATE TABLE bookmarks (
    user_id    uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    tweet_id   uuid         NOT NULL REFERENCES tweets (id) ON DELETE CASCADE,
    created_at timestamptz  NOT NULL,
    PRIMARY KEY (user_id, tweet_id)
);

-- Reverse lookup by tweet (used for tweet-deletion cascade and future counts).
CREATE INDEX idx_bookmarks_tweet_id ON bookmarks (tweet_id);

-- Deterministic bookmark-list ordering (newest bookmark first per user).
CREATE INDEX idx_bookmarks_user_id_created_at ON bookmarks (user_id, created_at);