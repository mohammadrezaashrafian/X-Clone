package logic_core.infrastructure.repository;

import logic_core.infrastructure.persistence.entity.notification.NotificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface NotificationJpaRepository
        extends JpaRepository<NotificationEntity, UUID>
{
    @Query("""
        SELECT n FROM NotificationEntity n
        WHERE n.recipient.id = :recipientId
        ORDER BY n.createdAt DESC
        """)
    List<NotificationEntity> findByRecipientOrderByCreatedAtDesc(
            @Param("recipientId") UUID recipientId);

    @Modifying
    @Query("""
        UPDATE NotificationEntity n
        SET n.isRead = true
        WHERE n.recipient.id = :recipientId
          AND n.isRead = false
        """)
    int markAllAsRead(@Param("recipientId") UUID recipientId);

    @Modifying
    @Query("""
        UPDATE NotificationEntity n
        SET n.isRead = true
        WHERE n.id = :id
        """)
    int markAsRead(@Param("id") UUID id);
}