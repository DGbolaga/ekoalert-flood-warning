package ng.ekoalert.domain.replay;

import ng.ekoalert.engine.Severity;

import java.time.Instant;
import java.util.List;

/**
 * What the engine would have predicted, had it been running.
 *
 * <p>Nothing here was delivered and nothing was written. Compare it against what
 * actually happened; the gaps are what the graph needs fixing for.
 */
public record ReplayResult(List<Escalation> escalations,
                           List<PredictedAlert> alerts,
                           List<AllClear> allClears,
                           Summary summary) {

    public record Escalation(String zoneId, Severity level, Instant at, int alertsProduced) {
    }

    /**
     * @param expectedArrival when water was predicted to reach the target, which
     *                        is the number to check against the historical record
     * @param wouldDeliver    false when the path runs through an edge that is not
     *                        confirmed. The row is still here so you can see what
     *                        confirming that edge would buy.
     */
    public record PredictedAlert(String originZone,
                                 String targetZone,
                                 Severity level,
                                 int etaMinutes,
                                 int hops,
                                 Instant firedAt,
                                 Instant expectedArrival,
                                 boolean wouldDeliver) {
    }

    public record AllClear(String originZone, String targetZone, Instant at) {
    }

    /**
     * @param suppressedByUnconfirmedPath alerts the current graph would have
     *                                    withheld. Usually the interesting number:
     *                                    it is the cost of edges nobody has
     *                                    confirmed yet.
     */
    public record Summary(int reportsReplayed,
                          int zonesEscalated,
                          int alertsPredicted,
                          int alertsDeliverable,
                          int suppressedByUnconfirmedPath,
                          Instant firstReportAt,
                          Instant lastReportAt) {
    }
}
