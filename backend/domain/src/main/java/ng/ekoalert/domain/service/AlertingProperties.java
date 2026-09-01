package ng.ekoalert.domain.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Everything about the alerting policy that a pilot might want to tune without
 * touching code. Defaults are the numbers in the build brief.
 */
@ConfigurationProperties(prefix = "ekoalert")
public class AlertingProperties {

    /** Two reports must fall inside this window of each other to form a quorum. */
    private Duration quorumWindow = Duration.ofMinutes(45);

    /** How many distinct verified reporters a quorum needs. */
    private int quorumSize = 2;

    /** A zone with no new report for this long clears, and an all-clear goes out. */
    private Duration deEscalationAfter = Duration.ofMinutes(90);

    /** Propagation depth. Errors compound, so this stays small. */
    private int maxHops = 3;

    /** Whether a path must be fully confirmed before its alerts are delivered. */
    private boolean requireConfirmedEdges = true;

    /** How many distinct residents it takes to confirm, reject, or create an edge. */
    private int correctionThreshold = 2;

    /**
     * Provisional surface flow rate used to estimate travel time for an edge
     * residents propose. The same rate that seeded the CSV. It is a placeholder
     * until observed timings replace it, not a hydrological constant.
     */
    private int flowRateMetersPerMinute = 55;

    /** No edge is instant, however short. */
    private int minTravelMinutes = 1;

    public Duration getQuorumWindow() {
        return quorumWindow;
    }

    public void setQuorumWindow(Duration quorumWindow) {
        this.quorumWindow = quorumWindow;
    }

    public int getQuorumSize() {
        return quorumSize;
    }

    public void setQuorumSize(int quorumSize) {
        this.quorumSize = quorumSize;
    }

    public Duration getDeEscalationAfter() {
        return deEscalationAfter;
    }

    public void setDeEscalationAfter(Duration deEscalationAfter) {
        this.deEscalationAfter = deEscalationAfter;
    }

    public int getMaxHops() {
        return maxHops;
    }

    public void setMaxHops(int maxHops) {
        this.maxHops = maxHops;
    }

    public boolean isRequireConfirmedEdges() {
        return requireConfirmedEdges;
    }

    public void setRequireConfirmedEdges(boolean requireConfirmedEdges) {
        this.requireConfirmedEdges = requireConfirmedEdges;
    }

    public int getCorrectionThreshold() {
        return correctionThreshold;
    }

    public void setCorrectionThreshold(int correctionThreshold) {
        this.correctionThreshold = correctionThreshold;
    }

    public int getFlowRateMetersPerMinute() {
        return flowRateMetersPerMinute;
    }

    public void setFlowRateMetersPerMinute(int flowRateMetersPerMinute) {
        this.flowRateMetersPerMinute = flowRateMetersPerMinute;
    }

    public int getMinTravelMinutes() {
        return minTravelMinutes;
    }

    public void setMinTravelMinutes(int minTravelMinutes) {
        this.minTravelMinutes = minTravelMinutes;
    }
}
