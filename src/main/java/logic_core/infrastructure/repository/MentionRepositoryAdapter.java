package logic_core.infrastructure.repository;

import logic_core.domain.model.TweetMention;
import logic_core.domain.repository.MentionRepository;
import logic_core.infrastructure.mapper.MentionEntityMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Component
@Transactional
public class MentionRepositoryAdapter implements MentionRepository
{
    private final TweetMentionJpaRepository tweetMentionJpaRepository;
    private final UserJpaRepository userJpaRepository;
    private final TweetJpaRepository tweetJpaRepository;

    public MentionRepositoryAdapter(
            TweetMentionJpaRepository tweetMentionJpaRepository,
            UserJpaRepository userJpaRepository,
            TweetJpaRepository tweetJpaRepository)
    {
        this.tweetMentionJpaRepository = tweetMentionJpaRepository;
        this.userJpaRepository = userJpaRepository;
        this.tweetJpaRepository = tweetJpaRepository;
    }

    @Override
    public void attachToTweet(TweetMention mention)
    {
        tweetMentionJpaRepository.save(MentionEntityMapper.toPersistence(
                mention,
                userJpaRepository.getReferenceById(mention.getMentionedUserId()),
                tweetJpaRepository.getReferenceById(mention.getTweetId())
        ));
    }

    @Override
    public void deleteByTweetId(UUID tweetId)
    {
        tweetMentionJpaRepository.deleteByTweetId(tweetId);
    }

    @Override
    public List<UUID> findMentionedUserIdsByTweetId(UUID tweetId)
    {
        return tweetMentionJpaRepository.findByTweet_Id(tweetId).stream()
                .map(MentionEntityMapper::toDomain)
                .map(TweetMention::getMentionedUserId)
                .toList();
    }
}