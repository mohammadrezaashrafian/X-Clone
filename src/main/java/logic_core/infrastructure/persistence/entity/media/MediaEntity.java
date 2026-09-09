package logic_core.infrastructure.persistence.entity.media;

import jakarta.persistence.*;
import logic_core.domain.model.media.MediaType;
import logic_core.infrastructure.persistence.entity.UserEntity;
import logic_core.infrastructure.persistence.entity.tweet.TweetEntity;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.OffsetDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "media", indexes = {
    @Index(name = "idx_media_tweet_id", columnList = "tweet_id"),
    @Index(name = "idx_media_uploaded_by", columnList = "uploaded_by")
})
public class MediaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private java.util.UUID id;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "tweet_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private TweetEntity tweet;

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "uploaded_by")
    private UserEntity uploadedBy;

    @Column(name = "media_url", nullable = false)
    private String mediaURL;

    @Column(name = "original_filename")
    private String originalFilename;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_type", nullable = false, length = 20)
    private MediaType mediaType;

    @Column(name = "display_order", nullable = false)
    private short displayOrder = 0;
}