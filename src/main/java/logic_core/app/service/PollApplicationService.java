package logic_core.app.service;

import logic_core.app.dto.request.PollRequest;
import logic_core.app.dto.response.PollResponse;
import logic_core.app.dto.validator.PollValidator;
import logic_core.app.mapper.PollMapper;
import logic_core.common.util.TimeProvider;
import logic_core.domain.model.PollModel;
import logic_core.domain.model.PollOptionModel;
import logic_core.domain.repository.PollRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Creates polls on behalf of tweet creation.
 *
 * <p>A poll is always created together with its tweet in the same transaction
 * (never as a standalone row for an unrelated tweet), and one poll per tweet
 * is enforced by the {@code uq_polls_tweet_id} database unique index.
 */
@Service
@RequiredArgsConstructor
public class PollApplicationService
{
    @NonNull private final PollValidator validator;
    @NonNull private final PollRepository pollRepository;
    @NonNull private final TimeProvider timeProvider;

    /**
     * Validates a poll payload without persisting anything. Used by tweet
     * creation so an invalid poll fails before the tweet row is written,
     * keeping tweet + poll creation atomic.
     */
    public void validate(PollRequest request)
    {
        validator.validate(request);
    }

    public PollResponse createPoll(PollRequest request, UUID tweetId)
    {
        validator.validate(request);

        OffsetDateTime now = timeProvider.now();

        List<PollOptionModel> options = new ArrayList<>();
        List<String> optionTexts = request.options();
        for (int i = 0; i < optionTexts.size(); i++)
        {
            options.add(PollOptionModel.create(
                    optionTexts.get(i).trim(),
                    (short) i
            ));
        }

        PollModel poll = PollModel.create(
                tweetId,
                request.question().trim(),
                now.plusMinutes(request.durationMinutes()),
                now,
                options
        );

        PollModel saved = pollRepository.create(poll);

        return PollMapper.toResponse(saved);
    }
}