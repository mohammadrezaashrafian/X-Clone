package logic_core.app.dto.validator;

import logic_core.app.dto.request.PollRequest;
import logic_core.common.exception.ValidationException;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PollValidator
{
    private static final int MAX_QUESTION_LENGTH = 255;
    private static final int MIN_OPTIONS = 2;
    private static final int MAX_OPTIONS = 20;
    private static final int MAX_OPTION_LENGTH = 255;
    private static final int MIN_DURATION_MINUTES = 1;
    private static final int MAX_DURATION_MINUTES = 7 * 24 * 60; // 7 days

    public void validate(PollRequest poll)
    {
        if (poll == null)
        {
            throw new ValidationException("poll.required");
        }

        if (poll.question() == null || poll.question().trim().isEmpty())
        {
            throw new ValidationException("poll.question.required");
        }

        if (poll.question().trim().length() > MAX_QUESTION_LENGTH)
        {
            throw new ValidationException("poll.question.too.long");
        }

        List<String> options = poll.options();

        if (options == null
                || options.size() < MIN_OPTIONS
                || options.size() > MAX_OPTIONS)
        {
            throw new ValidationException(
                    "poll.options.count.invalid." + MIN_OPTIONS + "." + MAX_OPTIONS
            );
        }

        for (String option : options)
        {
            if (option == null || option.trim().isEmpty())
            {
                throw new ValidationException("poll.option.blank");
            }
            if (option.trim().length() > MAX_OPTION_LENGTH)
            {
                throw new ValidationException("poll.option.too.long");
            }
        }

        if (poll.durationMinutes() < MIN_DURATION_MINUTES
                || poll.durationMinutes() > MAX_DURATION_MINUTES)
        {
            throw new ValidationException("poll.duration.invalid");
        }
    }
}