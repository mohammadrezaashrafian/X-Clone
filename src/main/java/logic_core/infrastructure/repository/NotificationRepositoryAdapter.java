package logic_core.infrastructure.repository;

import logic_core.domain.model.NotificationModel;
import logic_core.domain.repository.NotificationRepository;
import logic_core.infrastructure.mapper.NotificationEntityMapper;
import logic_core.infrastructure.persistence.entity.notification.NotificationEntity;
import logic_core.infrastructure.persistence.entity.tweet.TweetEntity;
import logic_core.infrastructure.persistence.entity.UserEntity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@Transactional
public class NotificationRepositoryAdapter implements NotificationRepository
{
    private final NotificationJpaRepository notificationJpaRepository;
    private final UserJpaRepository userJpaRepository;
    private final TweetJpaRepository tweetJpaRepository;

    public NotificationRepositoryAdapter(
            NotificationJpaRepository notificationJpaRepository,
            UserJpaRepository userJpaRepository,
            TweetJpaRepository tweetJpaRepository)
    {
        this.notificationJpaRepository = notificationJpaRepository;
        this.userJpaRepository = userJpaRepository;
        this.tweetJpaRepository = tweetJpaRepository;
    }

    @Override
    public Optional<NotificationModel> findById(UUID id)
    {
        return notificationJpaRepository.findById(id)
                .map(NotificationEntityMapper::toModel);
    }

    @Override
    public List<NotificationModel> findByRecipientId(UUID recipientId)
    {
        return notificationJpaRepository
                .findByRecipientOrderByCreatedAtDesc(recipientId)
                .stream()
                .map(NotificationEntityMapper::toModel)
                .toList();
    }

    @Override
    public void save(NotificationModel model)
    {
        UserEntity recipient =
                userJpaRepository.getReferenceById(model.getRecipientId());

        UserEntity actor =
                model.getActorId() != null
                        ? userJpaRepository.getReferenceById(model.getActorId())
                        : null;

        TweetEntity tweet =
                model.getTweetId() != null
                        ? tweetJpaRepository.getReferenceById(model.getTweetId())
                        : null;

        NotificationEntity entity = NotificationEntity.builder()
                .recipient(recipient)
                .actor(actor)
                .tweet(tweet)
                .type(model.getType())
                .isRead(model.isRead())
                .build();

        notificationJpaRepository.save(entity);
    }

    @Override
    public int markAllAsRead(UUID recipientId)
    {
        return notificationJpaRepository.markAllAsRead(recipientId);
    }

    @Override
    public int markAsRead(UUID id)
    {
        return notificationJpaRepository.markAsRead(id);
    }
}