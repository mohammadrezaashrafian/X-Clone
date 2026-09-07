package logic_core.domain.model.notification;

/**
 * The kinds of notifications the application can produce from existing
 * interactions. Mirrors the legacy {@code NotificationType} values that the
 * {@code notifications} table's {@code type} column was designed for.
 */
public enum NotificationType
{
    LIKE,
    REPLY,
    RETWEET,
    QUOTE,
    FOLLOW
}