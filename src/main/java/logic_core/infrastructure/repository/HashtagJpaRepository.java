package logic_core.infrastructure.repository;

import logic_core.infrastructure.persistence.entity.hashtag.HashtagEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface HashtagJpaRepository extends JpaRepository<HashtagEntity, UUID>
{
    Optional<HashtagEntity> findByTag(String tag);

    List<HashtagEntity> findByTagIn(Collection<String> tags);
}