package io.github.dlrkdgml0716.cachelab.item;

/** Non-final so NON_FINAL Redis typing can restore the concrete DTO type. */
public class Item {
    private final long id;
    private final long version;
    private final String value;

    @com.fasterxml.jackson.annotation.JsonCreator
    public Item(@com.fasterxml.jackson.annotation.JsonProperty("id") long id,
                @com.fasterxml.jackson.annotation.JsonProperty("version") long version,
                @com.fasterxml.jackson.annotation.JsonProperty("value") String value) {
        this.id = id;
        this.version = version;
        this.value = value;
    }
    @com.fasterxml.jackson.annotation.JsonProperty public long id() { return id; }
    @com.fasterxml.jackson.annotation.JsonProperty public long version() { return version; }
    @com.fasterxml.jackson.annotation.JsonProperty public String value() { return value; }
    @Override public boolean equals(Object other) {
        return other instanceof Item item && id == item.id && version == item.version
                && java.util.Objects.equals(value, item.value);
    }
    @Override public int hashCode() { return java.util.Objects.hash(id, version, value); }
}
