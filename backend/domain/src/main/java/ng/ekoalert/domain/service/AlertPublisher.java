package ng.ekoalert.domain.service;

import ng.ekoalert.domain.model.Alert;
import ng.ekoalert.engine.Severity;

import java.time.Instant;

/**
 * Outbound port for anything that leaves the system.
 *
 * <p>Implemented in the api module by the server-sent events stream. Declared
 * here so the domain can push without depending on the transport, and so tests
 * can record instead of send.
 *
 * <p>Only alerts and all-clears are gated by the kill switch. Zone status is map
 * data rather than a warning pushed at a person, so it keeps flowing while
 * alerts are halted; an admin who has just pulled the switch still needs to see
 * what the system thinks is happening.
 */
public interface AlertPublisher {

    /** A delivered alert. Suppressed alerts are written to the database but never reach here. */
    void alertFired(Alert alert);

    /**
     * A zone that had been alerting has cleared. This goes to everyone who
     * received an alert from that origin. It matters as much as the alert: if
     * people never see one, they stop trusting the warnings.
     */
    void allClear(String originZone, String targetZone, Instant at);

    /** A zone changed state on the map. */
    void zoneStatusChanged(String zoneId, Severity level, Instant at);
}
