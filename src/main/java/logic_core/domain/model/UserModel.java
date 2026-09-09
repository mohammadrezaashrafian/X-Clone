package logic_core.domain.model;

import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class UserModel
{
    private UUID id;

    private String username;
    private String email;
    private String passwordHash;

    private String displayName;
    private String bio;
    private String avatarUrl;
    private String bannerUrl;

    /** Blue-badge / account markers. */
    private boolean verified;
    private boolean active;
    private boolean deleted;

    /** Issue #20: email ownership verification state. */
    private boolean emailVerified;

    /** Issue #20: new email awaiting ownership verification; the old email
     *  stays authoritative until a successful confirmation. */
    private String pendingEmail;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;


    public static UserModel createNew(UUID id,String username,String displayName, String email, String passwordHash, OffsetDateTime now)
    {

        if (username == null || username.isBlank()) throw new IllegalArgumentException("Username is required");
        if (email == null || email.isBlank()) throw new IllegalArgumentException("Email is required");
        if (passwordHash == null || passwordHash.isBlank()) throw new IllegalArgumentException("Password hash is required");
        if (now == null) throw new IllegalArgumentException("Now is required");

        return UserModel.builder()
                .id(id)
                .username(username)
                .email(email)
                .passwordHash(passwordHash)
                .displayName(displayName)
                .bio(null)
                .avatarUrl(null)
                .bannerUrl(null)
                .verified(false)
                .active(true)
                .deleted(false)
                .emailVerified(false)
                .pendingEmail(null)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }


    public void updatePasswordHash(String newPasswordHash)
    {
        if (newPasswordHash == null || newPasswordHash.isBlank())
        {
            throw new IllegalArgumentException("Password hash cannot be empty");
        }
        this.passwordHash = newPasswordHash;
        this.updatedAt = OffsetDateTime.now();
    }

    public void verify()
    {
        this.verified = true;
        this.updatedAt = OffsetDateTime.now();
    }
}
