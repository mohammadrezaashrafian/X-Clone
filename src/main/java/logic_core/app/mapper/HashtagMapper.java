package logic_core.app.mapper;

import logic_core.app.dto.response.HashtagFollowResponse;

public final class HashtagMapper
{
    private HashtagMapper()
    {
    }

    public static HashtagFollowResponse toResponse(boolean following, long followersCount)
    {
        return new HashtagFollowResponse(following, followersCount);
    }
}