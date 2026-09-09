package logic_core.app.cache;

import logic_core.app.dto.response.PollOptionResponse;
import logic_core.app.dto.response.PollResponse;
import logic_core.app.dto.response.ProfileInfoResponse;
import logic_core.app.dto.response.TrendingHashtagsResponse;
import logic_core.app.dto.timeline.TimelineMedia;
import logic_core.app.dto.timeline.TimelineTweet;
import logic_core.domain.model.media.MediaType;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cache value serialization (V2.1 #19): stable DTO records must round-trip
 * through the cache codec losslessly, including {@link OffsetDateTime}
 * timestamps — this is exactly where a silent codec bug would corrupt cached
 * reads.
 */
class CacheJsonCodecTest
{
    private final CacheJsonCodec codec = new CacheJsonCodec();

    @Test
    void profileInfoResponseRoundTripsWithTimestamp()
    {
        OffsetDateTime joinedAt = OffsetDateTime.of(2026, 7, 1, 12, 30, 0, 0, ZoneOffset.ofHours(3));

        ProfileInfoResponse original = new ProfileInfoResponse(
                UUID.randomUUID(),
                "alice",
                "Alice",
                "hello world",
                "http://avatar",
                "http://banner",
                10,
                5,
                42,
                true,
                joinedAt);

        ProfileInfoResponse restored =
                codec.fromJson(codec.toJson(original), ProfileInfoResponse.class);

        assertThat(restored).isEqualTo(original);
        assertThat(restored.joinedAt()).isEqualTo(joinedAt);
    }

    @Test
    void timelineTweetWithPollAndMediaRoundTrips()
    {
        TimelineTweet original = TimelineTweet.builder()
                .tweetId(UUID.randomUUID())
                .authorId(UUID.randomUUID())
                .username("bob")
                .displayName("Bob")
                .avatarUrl("http://avatar")
                .content("Hello #cache")
                .likeCount(3)
                .replyCount(1)
                .retweetCount(0)
                .isLiked(false)
                .publishedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .media(List.of(
                        TimelineMedia.builder()
                                .mediaId(UUID.randomUUID())
                                .mediaUrl("http://media")
                                .mediaType(MediaType.IMAGE)
                                .displayOrder((short) 1)
                                .build()))
                .poll(PollResponse.builder()
                        .pollId(UUID.randomUUID())
                        .question("Q?")
                        .expiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusDays(1))
                        .expired(false)
                        .totalVotes(7)
                        .options(List.of(
                                PollOptionResponse.builder()
                                        .optionId(UUID.randomUUID())
                                        .text("A")
                                        .voteCount(4)
                                        .build()))
                        .build())
                .build();

        TimelineTweet restored =
                codec.fromJson(codec.toJson(original), TimelineTweet.class);

        assertThat(restored).isEqualTo(original);
    }

    @Test
    void nullCollectionsAndEnumsRoundTrip()
    {
        TimelineTweet original = TimelineTweet.builder()
                .tweetId(UUID.randomUUID())
                .authorId(UUID.randomUUID())
                .username("carol")
                .displayName("Carol")
                .content("no media no poll")
                .likeCount(0)
                .replyCount(0)
                .retweetCount(0)
                .isLiked(false)
                .publishedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .media(null)
                .poll(null)
                .build();

        TimelineTweet restored =
                codec.fromJson(codec.toJson(original), TimelineTweet.class);

        assertThat(restored.media()).isNull();
        assertThat(restored.poll()).isNull();
        assertThat(restored).isEqualTo(original);
    }

    @Test
    void trendingResponseWithNestedItemsRoundTrips()
    {
        TrendingHashtagsResponse original = TrendingHashtagsResponse.builder()
                .items(List.of(
                        new TrendingHashtagsResponse.TrendingHashtagItem("java", 12, 1),
                        new TrendingHashtagsResponse.TrendingHashtagItem("spring", 5, 2)))
                .totalItems(2)
                .windowDescription("last_24_hours_[start,end)")
                .build();

        TrendingHashtagsResponse restored =
                codec.fromJson(codec.toJson(original), TrendingHashtagsResponse.class);

        assertThat(restored).isEqualTo(original);
    }
}