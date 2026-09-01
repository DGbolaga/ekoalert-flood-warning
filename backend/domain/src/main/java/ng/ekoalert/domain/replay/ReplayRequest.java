package ng.ekoalert.domain.replay;

import ng.ekoalert.engine.Confidence;
import ng.ekoalert.engine.Severity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * A past flood event to run against the graph.
 *
 * @param edges    a graph snapshot to replay against. Empty or null replays
 *                 against the graph as it stands today, which is the usual case:
 *                 the question is whether the current graph would have got it
 *                 right.
 * @param reports  timestamped historical reports, in any order
 * @param settings overrides for the alerting policy, null for the live defaults
 */
public record ReplayRequest(List<EdgeSpec> edges,
                            List<ReportSpec> reports,
                            Settings settings) {

    public record EdgeSpec(String from, String to, int travelMinutes,
                           Confidence confidence, boolean blocked) {
    }

    public record ReportSpec(String zoneId, long reporterId, Severity level, Instant observedAt) {
    }

    /**
     * @param quorumWindow      how far apart two reports may be and still corroborate
     * @param deEscalationAfter how long a zone stays quiet before it clears
     */
    public record Settings(Integer maxHops,
                           Boolean requireConfirmedEdges,
                           Duration quorumWindow,
                           Integer quorumSize,
                           Duration deEscalationAfter) {
    }
}
