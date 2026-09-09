package logic_core.app.cache;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * JSON serialization for cache values (V2.1 #19).
 *
 * <p>Caches <b>stable DTO/projection records</b> (e.g. {@code ProfileInfoResponse},
 * {@code TimelineTweet}, {@code TrendingHashtagsResponse}) — never JPA entities,
 * never objects holding lazy relationships or persistence-context state. All
 * cached types are Java records / enums / primitives, which Gson serializes
 * reflectively; {@link OffsetDateTime} gets an explicit ISO-8601 adapter so
 * timestamps round-trip losslessly.
 *
 * <p>Cache JSON is an internal representation (the transport layer re-serializes
 * responses with its own Gson), so this codec only needs to be self-consistent.
 */
@Component
public class CacheJsonCodec
{
    private final Gson gson;

    public CacheJsonCodec()
    {
        this.gson = new GsonBuilder()
                .registerTypeAdapter(
                        OffsetDateTime.class,
                        (JsonSerializer<OffsetDateTime>) (src, typeOfSrc, context) ->
                                src == null
                                        ? JsonNull.INSTANCE
                                        : new JsonPrimitive(src.toString())
                )
                .registerTypeAdapter(
                        OffsetDateTime.class,
                        (JsonDeserializer<OffsetDateTime>) (json, typeOfT, context) ->
                                json.isJsonNull()
                                        ? null
                                        : OffsetDateTime.parse(json.getAsString())
                )
                .create();
    }

    public <T> String toJson(T value)
    {
        return gson.toJson(value);
    }

    public <T> T fromJson(String json, Class<T> type)
    {
        return gson.fromJson(json, type);
    }
}