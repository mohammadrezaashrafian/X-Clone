package logic_core.infrastructure.repository;

import logic_core.domain.model.PollModel;
import logic_core.domain.model.PollVote;
import logic_core.domain.repository.PollRepository;
import logic_core.infrastructure.mapper.PollEntityMapper;
import logic_core.infrastructure.persistence.entity.UserEntity;
import logic_core.infrastructure.persistence.entity.poll.PollEntity;
import logic_core.infrastructure.persistence.entity.poll.PollOptionEntity;
import logic_core.infrastructure.persistence.entity.poll.PollVoteEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
@Transactional
public class PollRepositoryAdapter implements PollRepository
{
    private final PollJpaRepository pollJpaRepository;
    private final PollOptionJpaRepository pollOptionJpaRepository;
    private final PollVoteJpaRepository pollVoteJpaRepository;
    private final UserJpaRepository userJpaRepository;
    private final TweetJpaRepository tweetJpaRepository;

    public PollRepositoryAdapter(
            PollJpaRepository pollJpaRepository,
            PollOptionJpaRepository pollOptionJpaRepository,
            PollVoteJpaRepository pollVoteJpaRepository,
            UserJpaRepository userJpaRepository,
            TweetJpaRepository tweetJpaRepository)
    {
        this.pollJpaRepository = pollJpaRepository;
        this.pollOptionJpaRepository = pollOptionJpaRepository;
        this.pollVoteJpaRepository = pollVoteJpaRepository;
        this.userJpaRepository = userJpaRepository;
        this.tweetJpaRepository = tweetJpaRepository;
    }

    @Override
    public PollModel create(PollModel poll)
    {
        PollEntity entity = PollEntityMapper.toPersistence(
                poll,
                tweetJpaRepository.getReferenceById(poll.getTweetId())
        );

        // saveAndFlush so the entity ids / generated timestamps are populated
        // before the poll is mapped back to a domain model.
        PollEntity saved = pollJpaRepository.saveAndFlush(entity);

        return PollEntityMapper.toDomain(saved, Map.of());
    }

    @Override
    public Optional<PollModel> findById(UUID pollId)
    {
        return pollJpaRepository.findById(pollId)
                .map(entity -> PollEntityMapper.toDomain(
                        entity,
                        votesByOption(entity.getId())
                ));
    }

    @Override
    public Optional<PollModel> findByTweetId(UUID tweetId)
    {
        return pollJpaRepository.findByTweet_Id(tweetId)
                .map(entity -> PollEntityMapper.toDomain(
                        entity,
                        votesByOption(entity.getId())
                ));
    }

    @Override
    public List<PollModel> findByTweetIds(Collection<UUID> tweetIds)
    {
        if (tweetIds == null || tweetIds.isEmpty())
        {
            return List.of();
        }

        List<PollEntity> entities = pollJpaRepository
                .findByTweetIdsWithOptions(tweetIds);

        if (entities.isEmpty())
        {
            return List.of();
        }

        List<UUID> pollIds = entities.stream().map(PollEntity::getId).toList();
        Map<UUID, Long> counts = toMap(
                pollVoteJpaRepository.countVotesByOptionIn(pollIds)
        );

        return entities.stream()
                .map(entity -> PollEntityMapper.toDomain(entity, counts))
                .toList();
    }

    @Override
    public boolean existsVote(UUID pollId, UUID userId)
    {
        return pollVoteJpaRepository.existsByPoll_IdAndUser_Id(pollId, userId);
    }

    @Override
    public void saveVote(PollVote vote)
    {
        PollVoteEntity entity = new PollVoteEntity();
        entity.setPoll(pollJpaRepository.getReferenceById(vote.getPollId()));
        entity.setUser(userReference(vote.getUserId()));
        entity.setOption(optionReference(vote.getOptionId()));
        entity.setVotedAt(vote.getVotedAt());
        pollVoteJpaRepository.save(entity);
    }

    @Override
    public void deleteByTweetId(UUID tweetId)
    {
        pollJpaRepository.deleteByTweetId(tweetId);
    }

    private Map<UUID, Long> votesByOption(UUID pollId)
    {
        return toMap(pollVoteJpaRepository.countVotesByOption(pollId));
    }

    private static Map<UUID, Long> toMap(List<Object[]> rows)
    {
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : rows)
        {
            counts.put((UUID) row[0], (Long) row[1]);
        }
        return counts;
    }

    private UserEntity userReference(UUID userId)
    {
        return userJpaRepository.getReferenceById(userId);
    }

    private PollOptionEntity optionReference(UUID optionId)
    {
        return pollOptionJpaRepository.getReferenceById(optionId);
    }
}