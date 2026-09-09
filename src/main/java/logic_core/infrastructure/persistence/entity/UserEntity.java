package logic_core.infrastructure.persistence.entity;

import logic_core.infrastructure.persistence.base.MutableEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "users", indexes = {@Index(name = "idx_users_is_deleted", columnList = "is_deleted")})
public class UserEntity extends MutableEntity {
    @Column(name = "username", unique = true, length = 50)
    private String username;

    @Column(name = "email", unique = true)
    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(name = "bio", length = 160)
    private String bio;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(name = "banner_url")
    private String bannerUrl;

    @Column(name = "is_verified", nullable = false)
    @ColumnDefault("false")
    private boolean isVerified = false;

    @Column(name = "is_active", nullable = false)
    @ColumnDefault("true")
    private boolean isActive = true;

    @Column(name = "email_verified", nullable = false)
    @ColumnDefault("false")
    private boolean emailVerified = false;

    @Column(name = "pending_email")
    private String pendingEmail;
}
