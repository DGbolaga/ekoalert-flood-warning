package ng.ekoalert.engine;

import java.util.Optional;

/**
 * How deep the water is. Ordered from least to most severe, and the ordering is
 * load-bearing: decay walks down it one step per hop.
 */
public enum Severity {

    ANKLE,
    KNEE,
    IMPASSABLE;

    /**
     * The level left after propagating this many hops from the origin. Severity
     * decays one step per hop and never escalates.
     *
     * <p>An IMPASSABLE origin is KNEE one hop out, ANKLE two hops out, and
     * nothing three hops out. Empty means the path has decayed below ANKLE and
     * must be dropped.
     */
    public Optional<Severity> decayedBy(int hops) {
        if (hops < 0) {
            throw new IllegalArgumentException("hops must not be negative: " + hops);
        }
        int index = ordinal() - hops;
        return index < 0 ? Optional.empty() : Optional.of(values()[index]);
    }
}
