package logic_core.app.facade;

import logic_core.app.dto.request.VotePollRequest;
import logic_core.app.dto.response.PollResponse;
import logic_core.app.usecase.poll.VotePollUseCase;
import logic_core.common.result.Result;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PollFacade
{
    private final VotePollUseCase votePollUseCase;

    public Result<PollResponse> vote(VotePollRequest request)
    {
        return votePollUseCase.execute(request);
    }
}