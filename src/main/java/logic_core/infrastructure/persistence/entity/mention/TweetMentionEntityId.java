package logic_core.infrastructure.persistence.entity.mention;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

@Getter
@Setter
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class TweetMentionEntityId implements Serializable
{
    private UUID mentionedUser;
    private UUID tweet;
}