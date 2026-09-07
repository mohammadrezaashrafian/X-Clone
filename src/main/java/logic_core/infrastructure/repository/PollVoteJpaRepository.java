package logic_core.infrastructure.repository;

import logic_core.infrastructure.persistence.entity.poll.PollVoteEntity;
import logic_core.infrastructure.persistence.entity.poll.PollVoteEntityId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface PollVoteJpaRepository
        extends JpaRepository<PollVoteEntity, PollVoteEntityId>
{
    boolean existsByPoll_IdAndUser_Id(UUID pollId, UUID userId);

    @Query("SELECT pv.option.id, COUNT(pv) FROM PollVoteEntity pv"
            + " WHERE pv.poll.id = :pollId GROUP BY pv.option.id")
    List<Object[]> countVotesByOption(@Param("pollId") UUID pollId);

    @Query("SELECT pv.option.id, COUNT(pv) FROM PollVoteEntity pv"
            + " WHERE pv.poll.id IN :pollIds GROUP BY pv.option.id")
    List<Object[]> countVotesByOptionIn(@Param("pollIds") Collection<UUID> pollIds);
}