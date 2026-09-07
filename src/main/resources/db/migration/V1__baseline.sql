-- ============================================================================
-- X-Clone — V1 baseline
--
-- Reproduces the complete application schema from an empty PostgreSQL database.
--
-- This is the single source of truth for schema creation. Hibernate runs with
-- `ddl-auto=validate` and must never create or mutate schema.
--
-- Scope:
--   * 13 entity-backed tables (users, sessions, tweets, likes, follows, blocks,
--     mutes, conversations, conversation_members, direct_messages, media,
--     notifications, tweet_edits)
--   * 7 legacy/orphaned tables intentionally preserved for compatibility
--     (hashtags, hashtag_follows, tweet_hashtags, tweet_mentions, polls,
--     poll_options, poll_votes)
--
-- Issue #6 schema decisions (vs. the historical Hibernate-generated schema):
--   1. users.username / users.email are NOT NULL (registration always requires
--      them; verified no NULL rows existed before the reset).
--   2. username/email uniqueness applies to ACTIVE accounts only, via partial
--      unique indexes (WHERE is_deleted = false), so a soft-deleted account's
--      identity becomes reusable by a new active account.
--   3. One active retweet marker per (author_id, retweet_of_id), enforced by a
--      partial unique index (WHERE is_deleted = false AND retweet_of_id IS NOT NULL).
--   4. notifications.type CHECK aligned to the application NotificationType
--      enum (LIKE, REPLY, RETWEET, QUOTE, FOLLOW). The historical superset
--      value 'MENTION' was dropped: no mention persistence exists in the
--      application and mention functionality is not implemented.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- users
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    id            uuid          PRIMARY KEY,
    created_at    timestamptz   NOT NULL,
    updated_at    timestamptz   NOT NULL,
    is_deleted    boolean       NOT NULL DEFAULT false,
    username      varchar(50)   NOT NULL,
    email         varchar(255)  NOT NULL,
    password_hash varchar(255),
    display_name  varchar(100),
    bio           varchar(160),
    avatar_url    varchar(255),
    banner_url    varchar(255),
    is_verified   boolean       NOT NULL DEFAULT false,
    is_active     boolean       NOT NULL DEFAULT true
);

CREATE INDEX idx_users_is_deleted ON users (is_deleted);

-- Username/email must be unique among ACTIVE accounts only; soft-deleted
-- identities are released for reuse.
CREATE UNIQUE INDEX uq_users_username_active
    ON users (username)
    WHERE is_deleted = false;

CREATE UNIQUE INDEX uq_users_email_active
    ON users (email)
    WHERE is_deleted = false;

-- ---------------------------------------------------------------------------
-- sessions
-- ---------------------------------------------------------------------------
CREATE TABLE sessions (
    id         uuid          PRIMARY KEY,
    created_at timestamptz   NOT NULL,
    token      varchar(255)  NOT NULL,
    expires_at timestamptz   NOT NULL,
    user_id    uuid          NOT NULL REFERENCES users (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_sessions_token ON sessions (token);
CREATE INDEX idx_sessions_user_id ON sessions (user_id);
CREATE INDEX idx_sessions_expires_at ON sessions (expires_at);

-- ---------------------------------------------------------------------------
-- tweets
-- ---------------------------------------------------------------------------
CREATE TABLE tweets (
    id            uuid          PRIMARY KEY,
    created_at    timestamptz   NOT NULL,
    updated_at    timestamptz   NOT NULL,
    is_deleted    boolean       NOT NULL DEFAULT false,
    content       varchar(280),
    published_at  timestamptz   NOT NULL,
    scheduled_at  timestamptz,
    is_pinned     boolean       NOT NULL DEFAULT false,
    author_id     uuid          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    reply_to_id   uuid          REFERENCES tweets (id) ON DELETE SET NULL,
    retweet_of_id uuid          REFERENCES tweets (id) ON DELETE SET NULL,
    quote_of_id   uuid          REFERENCES tweets (id) ON DELETE SET NULL
);

CREATE INDEX idx_tweets_author_id_published_at ON tweets (author_id, published_at);
CREATE INDEX idx_tweets_is_deleted ON tweets (is_deleted);
CREATE INDEX idx_tweets_published_at ON tweets (published_at);
CREATE INDEX idx_tweets_reply_to_id ON tweets (reply_to_id);
CREATE INDEX idx_tweets_retweet_of_id ON tweets (retweet_of_id);
CREATE INDEX idx_tweets_quote_of_id ON tweets (quote_of_id);

-- A retweet is a tweet row with retweet_of_id set. One user may hold at most
-- one ACTIVE retweet marker per original tweet; this is a database-level
-- backstop for the application duplicate protection. Soft-deleted markers are
-- excluded so re-reposting after un-retweet remains possible.
CREATE UNIQUE INDEX uq_tweets_active_retweet
    ON tweets (author_id, retweet_of_id)
    WHERE is_deleted = false AND retweet_of_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- likes
-- ---------------------------------------------------------------------------
CREATE TABLE likes (
    tweet_id   uuid         NOT NULL REFERENCES tweets (id) ON DELETE CASCADE,
    user_id    uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at timestamptz  NOT NULL,
    PRIMARY KEY (tweet_id, user_id)
);

CREATE INDEX idx_likes_tweet_id ON likes (tweet_id);

-- ---------------------------------------------------------------------------
-- follows
-- ---------------------------------------------------------------------------
CREATE TABLE follows (
    follower_id  uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    following_id uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at   timestamptz  NOT NULL,
    PRIMARY KEY (follower_id, following_id),
    CONSTRAINT no_self_follow CHECK (follower_id <> following_id)
);

CREATE INDEX idx_follows_following_id ON follows (following_id);

-- ---------------------------------------------------------------------------
-- blocks
-- ---------------------------------------------------------------------------
CREATE TABLE blocks (
    blocker_id uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    blocked_id uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at timestamptz  NOT NULL,
    PRIMARY KEY (blocked_id, blocker_id),
    CONSTRAINT no_self_block CHECK (blocker_id <> blocked_id)
);

CREATE INDEX idx_blocks_blocked_id ON blocks (blocked_id);

-- ---------------------------------------------------------------------------
-- mutes
-- ---------------------------------------------------------------------------
CREATE TABLE mutes (
    muter_id   uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    muted_id   uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at timestamptz  NOT NULL,
    PRIMARY KEY (muted_id, muter_id),
    CONSTRAINT no_self_mute CHECK (muter_id <> muted_id)
);

CREATE INDEX idx_mutes_muted_id ON mutes (muted_id);

-- ---------------------------------------------------------------------------
-- conversations
-- ---------------------------------------------------------------------------
CREATE TABLE conversations (
    id         uuid         PRIMARY KEY,
    created_at timestamptz  NOT NULL,
    updated_at timestamptz  NOT NULL,
    is_deleted boolean      NOT NULL DEFAULT false
);

-- ---------------------------------------------------------------------------
-- conversation_members
-- ---------------------------------------------------------------------------
CREATE TABLE conversation_members (
    conversation_id uuid         NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    user_id         uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    joined_at       timestamptz  NOT NULL,
    PRIMARY KEY (conversation_id, user_id)
);

CREATE INDEX idx_conversation_members_user_id ON conversation_members (user_id);

-- ---------------------------------------------------------------------------
-- direct_messages
-- ---------------------------------------------------------------------------
CREATE TABLE direct_messages (
    id              uuid          PRIMARY KEY,
    created_at      timestamptz   NOT NULL,
    updated_at      timestamptz   NOT NULL,
    is_deleted      boolean       NOT NULL DEFAULT false,
    content         varchar(255)  NOT NULL,
    is_read         boolean       NOT NULL DEFAULT false,
    is_edited       boolean       NOT NULL DEFAULT false,
    conversation_id uuid          NOT NULL REFERENCES conversations (id) ON DELETE CASCADE,
    sender_id       uuid          NOT NULL REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_direct_messages_conversation_id_created_at
    ON direct_messages (conversation_id, created_at);
CREATE INDEX idx_direct_messages_is_read ON direct_messages (is_read);
CREATE INDEX idx_direct_messages_sender_id ON direct_messages (sender_id);

-- ---------------------------------------------------------------------------
-- media
-- ---------------------------------------------------------------------------
CREATE TABLE media (
    id                 uuid         PRIMARY KEY,
    created_at         timestamptz  NOT NULL,
    tweet_id           uuid         NOT NULL REFERENCES tweets (id) ON DELETE CASCADE,
    media_url          varchar(255) NOT NULL,
    original_filename  varchar(255),
    file_size_bytes    bigint,
    media_type         varchar(20)  NOT NULL,
    display_order      smallint     NOT NULL,
    CONSTRAINT media_media_type_check
        CHECK (media_type IN ('IMAGE', 'VIDEO', 'GIF'))
);

CREATE INDEX idx_media_tweet_id ON media (tweet_id);

-- ---------------------------------------------------------------------------
-- notifications
-- ---------------------------------------------------------------------------
CREATE TABLE notifications (
    id           uuid         PRIMARY KEY,
    created_at   timestamptz  NOT NULL,
    recipient_id uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    actor_id     uuid         REFERENCES users (id) ON DELETE SET NULL,
    tweet_id     uuid         REFERENCES tweets (id) ON DELETE CASCADE,
    type         varchar(30)  NOT NULL,
    is_read      boolean      NOT NULL DEFAULT false,
    CONSTRAINT notifications_type_check
        CHECK (type IN ('LIKE', 'REPLY', 'RETWEET', 'QUOTE', 'FOLLOW'))
);

CREATE INDEX idx_notifications_recipient_id_is_read
    ON notifications (recipient_id, is_read);
CREATE INDEX idx_notifications_actor_id ON notifications (actor_id);
CREATE INDEX idx_notifications_tweet_id ON notifications (tweet_id);

-- ---------------------------------------------------------------------------
-- tweet_edits
-- ---------------------------------------------------------------------------
CREATE TABLE tweet_edits (
    id               uuid         PRIMARY KEY,
    created_at       timestamptz  NOT NULL,
    tweet_id         uuid         NOT NULL REFERENCES tweets (id) ON DELETE CASCADE,
    previous_content varchar(280) NOT NULL
);

CREATE INDEX idx_tweet_edits_tweet_id ON tweet_edits (tweet_id);

-- ============================================================================
-- Legacy tables (intentionally preserved — no entity/code representation today)
-- ============================================================================

-- ---------------------------------------------------------------------------
-- hashtags
-- ---------------------------------------------------------------------------
CREATE TABLE hashtags (
    id         uuid          PRIMARY KEY,
    created_at timestamptz   NOT NULL,
    tag        varchar(100)  NOT NULL
);

CREATE UNIQUE INDEX idx_hashtags_tag ON hashtags (tag);

-- ---------------------------------------------------------------------------
-- hashtag_follows
-- ---------------------------------------------------------------------------
CREATE TABLE hashtag_follows (
    hashtag_id uuid         NOT NULL REFERENCES hashtags (id) ON DELETE CASCADE,
    user_id    uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at timestamptz  NOT NULL,
    PRIMARY KEY (hashtag_id, user_id)
);

CREATE INDEX idx_hashtag_follows_hashtag_id ON hashtag_follows (hashtag_id);

-- ---------------------------------------------------------------------------
-- tweet_hashtags
-- ---------------------------------------------------------------------------
CREATE TABLE tweet_hashtags (
    hashtag_id  uuid      NOT NULL REFERENCES hashtags (id) ON DELETE CASCADE,
    tweet_id    uuid      NOT NULL REFERENCES tweets (id) ON DELETE CASCADE,
    usage_count integer   NOT NULL,
    PRIMARY KEY (hashtag_id, tweet_id)
);

CREATE INDEX idx_tweet_hashtags_hashtag_id ON tweet_hashtags (hashtag_id);

-- ---------------------------------------------------------------------------
-- tweet_mentions
-- ---------------------------------------------------------------------------
CREATE TABLE tweet_mentions (
    mentioned_user_id uuid  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    tweet_id          uuid  NOT NULL REFERENCES tweets (id) ON DELETE CASCADE,
    PRIMARY KEY (mentioned_user_id, tweet_id)
);

CREATE INDEX idx_tweet_mentions_mentioned_user_id ON tweet_mentions (mentioned_user_id);

-- ---------------------------------------------------------------------------
-- polls
-- ---------------------------------------------------------------------------
CREATE TABLE polls (
    id         uuid          PRIMARY KEY,
    created_at timestamptz   NOT NULL,
    tweet_id   uuid          NOT NULL REFERENCES tweets (id) ON DELETE CASCADE,
    question   varchar(255)  NOT NULL,
    expires_at timestamptz   NOT NULL
);

CREATE UNIQUE INDEX uq_polls_tweet_id ON polls (tweet_id);

-- ---------------------------------------------------------------------------
-- poll_options
-- ---------------------------------------------------------------------------
CREATE TABLE poll_options (
    id            uuid          PRIMARY KEY,
    created_at    timestamptz   NOT NULL,
    poll_id       uuid          NOT NULL REFERENCES polls (id) ON DELETE CASCADE,
    option_text   varchar(255)  NOT NULL,
    display_order smallint      NOT NULL
);

CREATE INDEX idx_poll_options_poll_id ON poll_options (poll_id);

-- ---------------------------------------------------------------------------
-- poll_votes
-- ---------------------------------------------------------------------------
CREATE TABLE poll_votes (
    poll_id   uuid         NOT NULL REFERENCES polls (id) ON DELETE CASCADE,
    user_id   uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    option_id uuid         NOT NULL REFERENCES poll_options (id) ON DELETE CASCADE,
    voted_at  timestamptz  NOT NULL,
    PRIMARY KEY (poll_id, user_id)
);

CREATE INDEX idx_poll_votes_poll_id ON poll_votes (poll_id);
CREATE INDEX idx_poll_votes_option_id ON poll_votes (option_id);