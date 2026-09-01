package ng.ekoalert.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An immutable snapshot of the drainage graph.
 *
 * <p>Adjacency is built once, at construction, so a single snapshot can be
 * propagated from many origins without rebuilding the index. Callers use
 * {@link #DrainageGraph(List)}; the canonical constructor exists only because a
 * record cannot hold a derived field any other way.
 *
 * <p>The graph is expected to contain cycles. Lagos drainage has them.
 */
public record DrainageGraph(List<Edge> edges, Map<ZoneId, List<Edge>> adjacency) {

    public DrainageGraph {
        Objects.requireNonNull(edges, "edges must not be null");
        Objects.requireNonNull(adjacency, "adjacency must not be null");
        edges = List.copyOf(edges);
        adjacency = Map.copyOf(adjacency);
    }

    /** Builds a snapshot from edges, indexing them by origin zone once. */
    public DrainageGraph(List<Edge> edges) {
        this(edges, index(edges));
    }

    /** Outbound edges of a zone, in the order they were supplied. Empty for an unknown zone. */
    public List<Edge> edgesFrom(ZoneId zone) {
        return adjacency.getOrDefault(zone, List.of());
    }

    private static Map<ZoneId, List<Edge>> index(List<Edge> edges) {
        Map<ZoneId, List<Edge>> byOrigin = new HashMap<>();
        for (Edge edge : edges) {
            byOrigin.computeIfAbsent(edge.from(), k -> new ArrayList<>()).add(edge);
        }
        byOrigin.replaceAll((zone, list) -> List.copyOf(list));
        return byOrigin;
    }
}
