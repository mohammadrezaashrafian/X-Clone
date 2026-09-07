package logic_core.infrastructure.persistence.entity.poll;

import jakarta.persistence.*;
import logic_core.infrastructure.persistence.base.BaseEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * JPA mapping for the existing {@code poll_options} table:
 *
 * <pre>
 * id            uuid PRIMARY KEY NOT NULL
 * created_at    timestamptz NOT NULL
 * poll_id       uuid NOT NULL → polls.id ON DELETE CASCADE
 * option_text   varchar(255) NOT NULL
 * display_order smallint NOT NULL
 * </pre>
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "poll_options", indexes = {
        @Index(name = "idx_poll_options_poll_id", columnList = "poll_id")
})
public class PollOptionEntity extends BaseEntity
{
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "poll_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private PollEntity poll;

    @Column(name = "option_text", nullable = false, length = 255)
    private String optionText;

    @Column(name = "display_order", nullable = false)
    private short displayOrder;
}