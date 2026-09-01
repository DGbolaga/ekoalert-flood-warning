package ng.ekoalert.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Best-first traversal by cumulative ETA, settling each zone once.
 *
 * <p>This is Dijkstra over travel minutes with three prunes layered on top:
 * untraversable edges, the hop cap, and severity decay. Because severity falls
 * one step per hop and hops only ever increase, a path that has decayed below
 * ANKLE can be abandoned rather than expanded. The settled set is what makes the
 * cycles in Lagos drainage terminate.
 */
public final class BestFirstPropagationEngine implements PropagationEngine {

    /** A zone reached by some path, waiting to be settled. */
    private record Step(ZoneId zone, int etaMinutes, int hops, boolean pathConfirmed) {
    }

    /**
     * Ties are broken by hops then zone id so that a run is reproducible. Replay
     * mode compares runs against each other, so a stable order is not cosmetic.
     */
    private static final Comparator<Step> BEST_FIRST = Comparator
            .comparingInt(Step::etaMinutes)
            .thenComparingInt(Step::hops)
            .thenComparing(step -> step.zone().value());

    @Override
    public List<PropagatedAlert> propagate(DrainageGraph graph,
                                           ZoneId origin,
                                           Severity originLevel,
                                           PropagationConfig config) {
        Objects.requireNonNull(graph, "graph must not be null");
        Objects.requireNonNull(origin, "origin must not be null");
        Objects.requireNonNull(originLevel, "originLevel must not be null");
        Objects.requireNonNull(config, "config must not be null");

        PriorityQueue<Step> frontier = new PriorityQueue<>(BEST_FIRST);
        Set<ZoneId> settled = new HashSet<>();
        List<PropagatedAlert> alerts = new ArrayList<>();

        frontier.add(new Step(origin, 0, 0, true));

        while (!frontier.isEmpty()) {
            Step step = frontier.poll();

            // First time a zone is polled is its lowest ETA. Later arrivals,
            // including any that came round a cycle, are already covered.
            if (!settled.add(step.zone())) {
                continue;
            }

            if (step.hops() > 0) {
                alerts.add(emit(step, originLevel, config));
            }

            int nextHops = step.hops() + 1;
            if (nextHops > config.maxHops()) {
                continue;
            }
            if (originLevel.decayedBy(nextHops).isEmpty()) {
                // Decayed below ANKLE. Walking further only decays further.
                continue;
            }

            for (Edge edge : graph.edgesFrom(step.zone())) {
                if (!edge.traversable() || settled.contains(edge.to())) {
                    continue;
                }
                frontier.add(new Step(
                        edge.to(),
                        step.etaMinutes() + edge.travelMinutes(),
                        nextHops,
                        step.pathConfirmed() && edge.confidence() == Confidence.CONFIRMED));
            }
        }

        return List.copyOf(alerts);
    }

    private PropagatedAlert emit(Step step, Severity originLevel, PropagationConfig config) {
        Optional<Severity> level = originLevel.decayedBy(step.hops());
        if (level.isEmpty()) {
            // Unreachable: a step is only enqueued once its hop depth is known
            // to survive decay.
            throw new IllegalStateException(
                    "enqueued a step that had already decayed below ANKLE: " + step);
        }
        boolean deliverable = !config.requireConfirmedEdges() || step.pathConfirmed();
        return new PropagatedAlert(
                step.zone(), level.get(), step.etaMinutes(), step.hops(), deliverable);
    }
}
