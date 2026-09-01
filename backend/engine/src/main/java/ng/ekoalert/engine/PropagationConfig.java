package ng.ekoalert.engine;

/**
 * Knobs for one propagation run.
 *
 * @param maxHops               how deep to walk. Errors compound and false
 *                              alarms destroy trust faster than missed alarms
 *                              do, so this stays small.
 * @param requireConfirmedEdges whether this run requires confirmation before a
 *                              result counts as deliverable. When true, a result
 *                              reports honestly whether every edge on its
 *                              winning path was confirmed. When false, the run
 *                              is not gating on confidence at all and every
 *                              result comes back deliverable. Either way the
 *                              same zones are returned; only
 *                              {@link PropagatedAlert#pathConfirmed()} moves.
 */
public record PropagationConfig(int maxHops, boolean requireConfirmedEdges) {

    /** Production defaults: three hops, confirmation required. */
    public static final PropagationConfig DEFAULT = new PropagationConfig(3, true);

    public PropagationConfig {
        if (maxHops < 0) {
            throw new IllegalArgumentException("maxHops must not be negative: " + maxHops);
        }
    }
}
