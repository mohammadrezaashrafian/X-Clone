-- ============================================================================
-- X-Clone — V4 advanced notifications (V2.1 #7)
--
-- Adds 'MENTION' to the notifications type CHECK constraint so tweet mentions
-- can generate notifications. All other notification semantics are unchanged:
-- the enum lives in the application and the column stays varchar(30).
-- ============================================================================

ALTER TABLE notifications
    DROP CONSTRAINT notifications_type_check;

ALTER TABLE notifications
    ADD CONSTRAINT notifications_type_check
        CHECK (type IN ('LIKE', 'REPLY', 'RETWEET', 'QUOTE', 'FOLLOW', 'MENTION'));
