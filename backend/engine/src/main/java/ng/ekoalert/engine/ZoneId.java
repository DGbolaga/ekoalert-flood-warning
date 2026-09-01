package ng.ekoalert.engine;

import java.util.Objects;

/**
 * Identifier of a zone: a street cluster, an estate, a ward. Opaque to the
 * engine, which never interprets it beyond equality.
 */
public record ZoneId(String value) {

    public ZoneId {
        Objects.requireNonNull(value, "zone id must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("zone id must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
