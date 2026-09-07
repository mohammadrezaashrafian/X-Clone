package logic_core.infrastructure.transport;

public enum RequestType
{
    AUTH_REGISTER,
    AUTH_LOGIN,
    AUTH_LOGOUT,
    AUTH_REFRESH,
    AUTH_REQUEST_PASSWORD_RESET,
    AUTH_VERIFY_PASSWORD_RESET_CODE,
    AUTH_RESET_PASSWORD,

    CONVERSATION_CREATE,
    MEMBER_ADD,
    MEMBER_DELETE,
    CONVERSATION_DELETE,
    CONVERSATION_GET,

    MESSAGE_SEND,
    MESSAGE_EDIT,
    MESSAGE_DELETE,
    MESSAGE_GET,
    MESSAGE_GET_CONVERSATION,

    RELATION_FOLLOW,
    RELATION_UNFOLLOW,
    RELATION_BLOCK,
    RELATION_UNBLOCK,
    RELATION_MUTE,
    RELATION_UNMUTE,

    TIMELINE_GET,

    TWEET_CREATE,
    TWEET_DELETE,
    TWEET_EDIT,
    TWEET_LIKE,
    TWEET_UNLIKE,
    TWEET_REPLY,
    TWEET_RETWEET,
    TWEET_UNRETWEET,
    TWEET_GET,
    TWEET_GET_REPLIES,

    NOTIFICATION_GET,
    NOTIFICATION_READ,
    NOTIFICATION_READ_ALL,

    USER_GET_PROFILE,
    USER_SEARCH,
    USER_UPDATE_PROFILE,
    USER_UPDATE_BIO,
    USER_UPDATE_AVATAR,
    USER_UPDATE_BANNER,
    USER_UPDATE_EMAIL,
    USER_UPDATE_PASSWORD,
    USER_DELETE_ACCOUNT,
    USER_UPDATE_COMPLETE_PROFILE,
    USER_GET_IS_FOLLOW,
    USER_GET_IS_LIKE,

    FOLLOW_GET_FOLLOWINGS,
    FOLLOW_GET_FOLLOWERS,

    MEDIA_DELETE,
    MEDIA_DOWNLOAD;

    public static RequestType fromWire(String value)
    {
        if (value == null || value.isBlank())
        {
            return null;
        }

        try
        {
            return RequestType.valueOf(value);
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
    }

    public String toWire()
    {
        return name();
    }

    public String toResponseWire()
    {
        return name() + "_RESPONSE";
    }
}