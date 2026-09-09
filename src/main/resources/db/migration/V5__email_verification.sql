-- ============================================================================
-- X-Clone — V5: email ownership verification (Issue #20)
--
-- Adds two additive columns to users:
--
--   * email_verified  – whether the account owner proved ownership of the
--                       current email address (self-service verification;
--                       registration/login are NOT gated on it).
--   * pending_email   – the new address awaiting ownership verification in
--                       the secure email-change flow. The old email remains
--                       authoritative until EMAIL_CHANGE_CONFIRM succeeds.
--
-- Backward compatible: existing rows are treated as not verified with no
-- pending change (the default for every previously registered account).
-- Rollback: DROP COLUMN email_verified, pending_email (safe, additive only).
-- ============================================================================

ALTER TABLE users
    ADD COLUMN email_verified boolean NOT NULL DEFAULT false;

ALTER TABLE users
    ADD COLUMN pending_email varchar(255);