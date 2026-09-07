package logic_core.infrastructure.mapper;

import logic_core.domain.model.PollModel;
import logic_core.domain.model.PollOptionModel;
import logic_core.infrastructure.persistence.entity.poll.PollEntity;
import logic_core.infrastructure.persistence.entity.poll.PollOptionEntity;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public final class PollEntityMapper
{
    private PollEntityMapper()
    {
    }

    /**
     * Maps a loaded poll entity (with its options) to the domain model,
     * attaching the per-option vote counts.
     *
     * @param votesByOption optionId → vote count (may be empty)
     */
    public static PollModel toDomain(PollEntity entity, Map<UUID, Long> votesByOption)
    {
        if (entity == null)
        {
            return null;
        }

        List<PollOptionModel> options = entity.getOptions().stream()
                .sorted(Comparator.comparingInt(PollOptionEntity::getDisplayOrder))
                .map(option -> PollOptionModel.restore(
                        option.getId(),
                        option.getOptionText(),
                        option.getDisplayOrder(),
                        votesByOption.getOrDefault(option.getId(), 0L)
                ))
                .collect(Collectors.toList());

        long totalVotes = options.stream()
                .mapToLong(PollOptionModel::getVoteCount)
                .sum();

        return PollModel.restore(
                entity.getId(),
                entity.getTweet().getId(),
                entity.getQuestion(),
                entity.getExpiresAt(),
                entity.getCreatedAt(),
                options,
                totalVotes
        );
    }

    /**
     * Maps a new domain poll (with its options) to a persistable entity tree.
     * The caller must provide the resolved tweet reference.
     */
    public static PollEntity toPersistence(
            PollModel poll,
            logic_core.infrastructure.persistence.entity.tweet.TweetEntity tweet)
    {
        PollEntity entity = new PollEntity();
        entity.setTweet(tweet);
        entity.setQuestion(poll.getQuestion());
        entity.setExpiresAt(poll.getExpiresAt());

        for (PollOptionModel option : poll.getOptions())
        {
            PollOptionEntity optionEntity = new PollOptionEntity();
            optionEntity.setPoll(entity);
            optionEntity.setOptionText(option.getText());
            optionEntity.setDisplayOrder(option.getDisplayOrder());
            entity.getOptions().add(optionEntity);
        }

        return entity;
    }
}