-- ============================================================================
-- X-Clone — V3 media upload
--
-- Enables the upload-before-attach tweet media flow (V2.1 #6):
--
--   1. media.tweet_id becomes nullable so a media record can exist before it
--      is attached to a tweet.
--   2. media.uploaded_by records the owning user, so attachment can be
--      authorized against the authenticated actor and so a user can never
--      attach another user's uploaded media.
--
-- Existing attached-media rows and the avatar/banner flows are unaffected:
-- tweet_id keeps its FK/cascade for attached rows, and profile images are
-- stored on disk (MediaStorageService) rather than in this table.
-- ============================================================================

ALTER TABLE media
    ALTER COLUMN tweet_id DROP NOT NULL;

ALTER TABLE media
    ADD COLUMN uploaded_by uuid REFERENCES users (id) ON DELETE SET NULL;

CREATE INDEX idx_media_uploaded_by ON media (uploaded_by);