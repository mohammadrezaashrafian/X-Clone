package logic_core.infrastructure.persistence.entity.hashtag;

import jakarta.persistence.*;
import logic_core.infrastructure.persistence.base.BaseEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * JPA mapping for the existing {@code hashtags} table (part of the V2
 * baselined schema):
 *
 * <pre>
 * id         uuid PRIMARY KEY NOT NULL
 * created_at timestamptz NOT NULL
 * tag        varchar(100) NOT NULL  (unique via idx_hashtags_tag)
 * </pre>
 *
 * <p>The {@code tag} uniqueness is enforced by the database's unique index
 * {@code idx_hashtags_tag}; no DDL is introduced here.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "hashtags", indexes = {
        @Index(name = "idx_hashtags_tag", columnList = "tag", unique = true)
})
public class HashtagEntity extends BaseEntity
{
    @Column(name = "tag", nullable = false, length = 100)
    private String tag;
}