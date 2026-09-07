package logic_core.app.mapper;

import logic_core.app.dto.response.PollOptionResponse;
import logic_core.app.dto.response.PollResponse;
import logic_core.domain.model.PollModel;
import logic_core.domain.model.PollOptionModel;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PollMapper
{
    private PollMapper()
    {
    }

    public static PollResponse toResponse(PollModel poll)
    {
        if (poll == null)
        {
            return null;
        }

        List<PollOptionResponse> options = poll.getOptions().stream()
                .map(PollMapper::toOptionResponse)
                .toList();

        return PollResponse.builder()
                .pollId(poll.getId())
                .question(poll.getQuestion())
                .expiresAt(poll.getExpiresAt())
                .expired(poll.getExpiresAt().isBefore(OffsetDateTime.now()))
                .totalVotes(poll.getTotalVotes())
                .options(options)
                .build();
    }

    /**
     * Maps a batch of polls into {@code tweetId → PollResponse} for timeline
     * read-model enrichment. Tweets without a poll are simply absent from the
     * map; callers resolve missing keys to {@code null} poll.
     */
    public static Map<UUID, PollResponse> toResponsesByTweet(
            Collection<PollModel> polls)
    {
        Map<UUID, PollResponse> byTweet = new HashMap<>();
        if (polls == null)
        {
            return byTweet;
        }
        for (PollModel poll : polls)
        {
            if (poll != null && poll.getTweetId() != null)
            {
                byTweet.put(poll.getTweetId(), toResponse(poll));
            }
        }
        return byTweet;
    }

    private static PollOptionResponse toOptionResponse(PollOptionModel option)
    {
        return PollOptionResponse.builder()
                .optionId(option.getId())
                .text(option.getText())
                .voteCount(option.getVoteCount())
                .build();
    }
}