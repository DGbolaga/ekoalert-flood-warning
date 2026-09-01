package ng.ekoalert.engine;

import java.util.List;

/**
 * Walks a drainage graph downstream from an origin and reports the zones water
 * is expected to reach.
 *
 * <p>Implementations perform no I/O and hold no state between calls.
 */
public interface PropagationEngine {

    /**
     * @param origin      the zone that escalated
     * @param originLevel the level observed at the origin
     * @return one entry per reachable zone, ordered by ascending ETA. Never null.
     */
    List<PropagatedAlert> propagate(DrainageGraph graph,
                                    ZoneId origin,
                                    Severity originLevel,
                                    PropagationConfig config);
}
