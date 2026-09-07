package logic_core.infrastructure.repository;

import logic_core.infrastructure.persistence.entity.poll.PollOptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface PollOptionJpaRepository extends JpaRepository<PollOptionEntity, UUID>
{
}