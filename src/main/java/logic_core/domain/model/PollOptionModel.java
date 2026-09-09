package logic_core.domain.model;

import lombok.Getter;

import java.util.Objects;
import java.util.UUID;

/**
 * Domain representation of a single poll option and its derived vote count.
 */
@Getter
public class PollOptionModel
{
    private final UUID id;
    private final String text;
    private final short displayOrder;
    private final long voteCount;

    private PollOptionModel(UUID id, String text, short displayOrder, long voteCount)
    {
        this.id = Objects.requireNonNull(id, "PollOption.id cannot be null");
        this.text = Objects.requireNonNull(text, "PollOption.text cannot be null");
        this.displayOrder = displayOrder;
        this.voteCount = voteCount;
    }

    public static PollOptionModel create(String text, short displayOrder)
    {
        return new PollOptionModel(UUID.randomUUID(), text, displayOrder, 0L);
    }

    public static PollOptionModel restore(UUID id, String text, short displayOrder, long voteCount)
    {
        return new PollOptionModel(id, text, displayOrder, voteCount);
    }
}