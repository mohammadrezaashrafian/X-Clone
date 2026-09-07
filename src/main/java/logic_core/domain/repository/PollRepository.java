package logic_core.domain.repository;

import logic_core.domain.model.PollModel;
import logic_core.domain.model.PollVote;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PollRepository
{
    /**
     * Persist a new poll together with its options.
     */
    PollModel create(PollModel poll);

    /**
     * Load a poll (with options and derived vote counts) by its own id.
     */
    Optional<PollModel> findById(UUID pollId);

    /**
     * Load the poll attached to a tweet, if any.
     */
    Optional<PollModel> findByTweetId(UUID tweetId);

    /**
     * Load polls for a set of tweets (for timeline read-model enrichment).
     * Tweets without a poll are simply absent from the result.
     */
    List<PollModel> findByTweetIds(Collection<UUID> tweetIds);

    /**
     * Whether the user already voted in the poll (duplicate-vote prevention).
     */
    boolean existsVote(UUID pollId, UUID userId);

    /**
     * Persist a single user's vote.
     */
    void saveVote(PollVote vote);

    /**
     * Hard-delete the poll (and its options/votes via FK CASCADE) belonging to
     * a tweet. Used during tweet deletion to cascade-delete poll data alongside
     * likes, media, mentions and bookmarks.
     */
    void deleteByTweetId(UUID tweetId);
}