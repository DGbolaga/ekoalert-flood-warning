package ng.ekoalert.engine;

import java.util.Objects;

/**
 * One zone the engine expects water to reach.
 *
 * @param target        the zone water is expected to reach
 * @param level         severity at that zone, decayed one step per hop
 * @param etaMinutes    sum of travel minutes along the winning path
 * @param hops          number of edges walked to get here
 * @param pathConfirmed whether the caller may deliver this to residents. False
 *                      when the winning path contains an edge that is not
 *                      confirmed and the run required confirmation. The engine
 *                      still returns the row so the map can draw it; suppressing
 *                      delivery is the caller's job.
 */
public record PropagatedAlert(ZoneId target,
                              Severity level,
                              int etaMinutes,
                              int hops,
                              boolean pathConfirmed) {

    public PropagatedAlert {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(level, "level must not be null");
        if (etaMinutes < 0) {
            throw new IllegalArgumentException("etaMinutes must not be negative: " + etaMinutes);
        }
        if (hops < 1) {
            throw new IllegalArgumentException("hops must be at least 1: " + hops);
        }
    }
}
