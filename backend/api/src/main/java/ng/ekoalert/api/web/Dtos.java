package ng.ekoalert.api.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import ng.ekoalert.engine.Confidence;
import ng.ekoalert.engine.Severity;

import java.time.Instant;
import java.util.List;

/** Wire shapes for v1. Kept together because each one is a handful of lines. */
public final class Dtos {

    private Dtos() {
    }

    // ---------- auth ----------

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record LoginResponse(String token, String role, Long reporterId, Instant expiresAt) {
    }

    // ---------- reports ----------

    /**
     * @param zoneId       optional. A reporter is vetted for one zone; supplying a
     *                     different one is an error rather than a silent override.
     * @param drainBlocked optional one tap field. Null means the reporter said
     *                     nothing about the drain, which is not the same as saying
     *                     it is clear.
     * @param observedAt   optional, defaults to now. Present so a reporter can log
     *                     water they saw twenty minutes ago on their way home.
     */
    public record ReportRequest(String zoneId,
                                @NotNull Severity level,
                                Boolean drainBlocked,
                                Instant observedAt) {
    }

    public record ReportResponse(long reportId,
                                 String zoneId,
                                 Severity level,
                                 Instant observedAt,
                                 boolean countedTowardQuorum,
                                 Severity quorumLevel,
                                 boolean escalated,
                                 List<AlertView> alerts) {
    }

    public record AlertView(long id,
                            String originZone,
                            String targetZone,
                            Severity level,
                            int etaMinutes,
                            int hops,
                            Instant firedAt,
                            String suppressedBy) {
    }

    // ---------- zones and graph ----------

    public record ZoneStatusView(Severity level, Instant escalatedAt, Instant clearedAt, boolean active) {

        public static ZoneStatusView dry() {
            return new ZoneStatusView(null, null, null, false);
        }
    }

    /**
     * @param name     null until a field survey fills it. Clients show
     *                 {@code displayName} instead of inventing one.
     * @param landmark null for the same reason
     */
    public record ZoneSummary(String id,
                              String corridor,
                              String name,
                              String landmark,
                              String displayName,
                              double lat,
                              double lng,
                              boolean needsFieldNaming,
                              ZoneStatusView status) {
    }

    public record ZoneDetail(ZoneSummary zone,
                             List<EdgeView> outbound,
                             List<EdgeView> inbound) {
    }

    /**
     * @param alertable derived, never stored: an edge only carries alerts when it
     *                  is confirmed and not blocked
     */
    public record EdgeView(long id,
                           String fromZone,
                           String toZone,
                           int travelMinutes,
                           Integer distanceM,
                           Confidence confidence,
                           boolean blocked,
                           boolean alertable,
                           String inferenceBasis,
                           Instant updatedAt,
                           Long confirmations,
                           Long rejections) {
    }

    public record GraphResponse(List<ZoneSummary> zones, List<EdgeView> edges, GraphCounts counts) {
    }

    public record GraphCounts(long zones, long edges, long inferred, long confirmed, long rejected, long blocked) {
    }

    // ---------- corrections ----------

    public record ProposeEdgeRequest(@NotBlank String fromZone, @NotBlank String toZone) {
    }

    /**
     * @param distinctVoices how many separate residents have taken this action
     * @param thresholdMet   whether this tap was the one that changed the edge
     */
    public record CorrectionResponse(long correctionId,
                                     String action,
                                     String fromZone,
                                     String toZone,
                                     long distinctVoices,
                                     long threshold,
                                     boolean thresholdMet,
                                     EdgeView edge) {
    }

    // ---------- proposed places ----------

    /**
     * Naming somewhere the map has no node for. Position is optional: the person
     * who knows what a place is called is not always the person standing at it.
     */
    public record ProposePlaceRequest(@NotBlank String fromZone,
                                      @NotBlank String landmark,
                                      Double lat,
                                      Double lng) {
    }

    /** Affirming somebody else's place, optionally supplying the position it lacked. */
    public record AffirmPlaceRequest(Double lat, Double lng) {
    }

    /**
     * @param located        false while nobody has supplied a position, which
     *                       blocks promotion however many voices it has
     * @param distinctVoices how many separate residents say this place is real
     * @param promoted       whether this tap was the one that put it on the map
     * @param mergedInto     true when the place turned out to be an existing zone
     *                       under a name residents actually use
     * @param zone           the zone it became, absent until promoted
     * @param edge           the inferred edge to it, absent until promoted
     */
    public record PlaceView(long id,
                            String landmark,
                            Double lat,
                            Double lng,
                            boolean located,
                            String fromZone,
                            String status,
                            long distinctVoices,
                            long threshold,
                            boolean promoted,
                            boolean mergedInto,
                            Instant proposedAt,
                            ZoneSummary zone,
                            EdgeView edge) {
    }

    // ---------- subscriptions ----------

    public record SubscriptionRequest(@NotBlank String zoneId, String channel, @NotBlank String address) {
    }

    public record SubscriptionResponse(long id, String zoneId, String channel, String address) {
    }

    // ---------- admin ----------

    public record KillSwitchRequest(@NotNull Boolean enabled) {
    }

    public record KillSwitchResponse(boolean alertsEnabled, Instant at) {
    }

    public record SuspendRequest(Boolean suspended) {
    }

    public record ReporterView(long id, String zoneId, String displayName, boolean suspended, Instant verifiedAt) {
    }

    public record ApiError(String error, String message, Instant at) {
    }
}
