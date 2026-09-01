package ng.ekoalert.engine;

import java.util.Objects;

/**
 * A directed observational claim: water reported in {@code from} tends to appear
 * in {@code to} roughly {@code travelMinutes} later.
 *
 * <p>A self edge is impossible by database schema, but the engine accepts one
 * without complaint and must not loop on it. Rejecting it here would move a
 * schema concern into the engine.
 */
public record Edge(ZoneId from,
                   ZoneId to,
                   int travelMinutes,
                   Confidence confidence,
                   boolean blocked) {

    public Edge {
        Objects.requireNonNull(from, "edge from must not be null");
        Objects.requireNonNull(to, "edge to must not be null");
        Objects.requireNonNull(confidence, "edge confidence must not be null");
        if (travelMinutes < 0) {
            throw new IllegalArgumentException(
                    "travelMinutes must not be negative: " + travelMinutes);
        }
    }

    /**
     * Whether the engine may walk this edge at all. Blocked edges carry no
     * water because the drain has silted up or been built over. Rejected edges
     * were contradicted by residents and are never traversed, whatever the
     * configuration says.
     */
    public boolean traversable() {
        return !blocked && confidence != Confidence.REJECTED;
    }
}
