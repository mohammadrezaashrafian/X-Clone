package logic_core.infrastructure.repository;

import logic_core.infrastructure.persistence.entity.hashtag.HashtagFollowEntity;
import logic_core.infrastructure.persistence.entity.hashtag.HashtagFollowEntityId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface HashtagFollowJpaRepository
        extends JpaRepository<HashtagFollowEntity, HashtagFollowEntityId>
{
    boolean existsByHashtag_IdAndUser_Id(UUID hashtagId, UUID userId);

    Optional<HashtagFollowEntity> findByHashtag_IdAndUser_Id(UUID hashtagId, UUID userId);

    long countByHashtag_Id(UUID hashtagId);
}