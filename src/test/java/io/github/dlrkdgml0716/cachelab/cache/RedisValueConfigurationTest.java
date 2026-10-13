package io.github.dlrkdgml0716.cachelab.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dlrkdgml0716.cachelab.item.Item;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class RedisValueConfigurationTest {
    @Test
    void redisRoundTripRestoresConcreteItemAndGenerationWithoutExplicitTargetType() {
        var serializer = RedisValueConfiguration.valueSerializer();
        var item = new Item(1, 7, "sample");
        byte[] bytes = serializer.serialize(item);
        assertTrue(new String(bytes, StandardCharsets.UTF_8).contains("@class"));
        assertEquals(item, assertInstanceOf(Item.class, serializer.deserialize(bytes)));
        assertEquals("generation-token", serializer.deserialize(serializer.serialize("generation-token")));
    }

    @Test
    void httpJsonStillHasOnlyTheDomainFields() throws Exception {
        var mapper = new ObjectMapper();
        var item = new Item(1, 7, "sample");
        var json = mapper.readTree(mapper.writeValueAsBytes(item));
        assertEquals(3, json.size());
        assertEquals(1, json.get("id").asLong());
        assertEquals(7, json.get("version").asLong());
        assertEquals("sample", json.get("value").asText());
        assertEquals(item, mapper.readValue(json.toString(), Item.class));
    }
}
