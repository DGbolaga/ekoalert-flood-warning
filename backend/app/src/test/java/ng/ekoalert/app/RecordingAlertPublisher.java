package ng.ekoalert.app;

import ng.ekoalert.domain.model.Alert;
import ng.ekoalert.domain.service.AlertPublisher;
import ng.ekoalert.engine.Severity;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Records what would have left the building.
 *
 * <p>Standing in for the server-sent events publisher, because what these tests
 * need to know is what was sent, not how.
 */
public class RecordingAlertPublisher implements AlertPublisher {

    public record Fired(String originZone, String targetZone, Severity level, int etaMinutes) {
    }

    public record Cleared(String originZone, String targetZone) {
    }

    public final List<Fired> fired = new ArrayList<>();
    public final List<Cleared> allClears = new ArrayList<>();
    public final List<String> statusChanges = new ArrayList<>();

    public void reset() {
        fired.clear();
        allClears.clear();
        statusChanges.clear();
    }

    @Override
    public void alertFired(Alert alert) {
        fired.add(new Fired(alert.getOriginZone(), alert.getTargetZone(),
                alert.getLevel(), alert.getEtaMinutes()));
    }

    @Override
    public void allClear(String originZone, String targetZone, Instant at) {
        allClears.add(new Cleared(originZone, targetZone));
    }

    @Override
    public void zoneStatusChanged(String zoneId, Severity level, Instant at) {
        statusChanges.add(zoneId + "=" + (level == null ? "CLEAR" : level.name()));
    }

    @TestConfiguration
    public static class Config {

        @Bean
        @Primary
        public RecordingAlertPublisher recordingAlertPublisher() {
            return new RecordingAlertPublisher();
        }
    }
}
