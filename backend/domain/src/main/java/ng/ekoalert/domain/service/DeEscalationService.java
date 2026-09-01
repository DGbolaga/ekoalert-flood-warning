package ng.ekoalert.domain.service;

import ng.ekoalert.domain.model.Alert;
import ng.ekoalert.domain.model.ZoneStatus;
import ng.ekoalert.domain.repo.AlertRepository;
import ng.ekoalert.domain.repo.ReportRepository;
import ng.ekoalert.domain.repo.ZoneStatusRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Clears zones that have gone quiet and sends the all-clear.
 *
 * <p>The all-clear goes to everyone who received an alert from that origin, so
 * it is driven off the alert rows that were actually delivered. A suppressed
 * alert reached nobody and gets no all-clear.
 */
@Service
public class DeEscalationService {

    private static final Logger log = LoggerFactory.getLogger(DeEscalationService.class);

    private final ZoneStatusRepository zoneStatuses;
    private final ReportRepository reports;
    private final AlertRepository alerts;
    private final AlertPublisher publisher;
    private final AlertingProperties properties;

    public DeEscalationService(ZoneStatusRepository zoneStatuses,
                               ReportRepository reports,
                               AlertRepository alerts,
                               AlertPublisher publisher,
                               AlertingProperties properties) {
        this.zoneStatuses = zoneStatuses;
        this.reports = reports;
        this.alerts = alerts;
        this.publisher = publisher;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${ekoalert.de-escalation-sweep:PT1M}")
    public void scheduledSweep() {
        sweep(Instant.now());
    }

    /**
     * @return the zones cleared by this sweep
     */
    @Transactional
    public List<String> sweep(Instant now) {
        Instant cutoff = now.minus(properties.getDeEscalationAfter());
        List<String> cleared = new ArrayList<>();

        for (ZoneStatus status : zoneStatuses.findActive()) {
            Instant lastReport = reports.findLatestObservedAt(status.getZoneId());
            Instant lastActivity = lastReport != null ? lastReport : status.getEscalatedAt();
            if (!lastActivity.isBefore(cutoff)) {
                continue;
            }

            Instant escalatedAt = status.getEscalatedAt();
            status.clear(now);
            zoneStatuses.save(status);
            cleared.add(status.getZoneId());

            for (String target : deliveredTargetsSince(status.getZoneId(), escalatedAt)) {
                publisher.allClear(status.getZoneId(), target, now);
            }
            publisher.zoneStatusChanged(status.getZoneId(), null, now);

            log.info("zone {} cleared after {} of quiet", status.getZoneId(), properties.getDeEscalationAfter());
        }

        return cleared;
    }

    /** Distinct target zones that heard from this origin during the episode just ended. */
    private Set<String> deliveredTargetsSince(String originZone, Instant escalatedAt) {
        Set<String> targets = new LinkedHashSet<>();
        for (Alert alert : alerts.findByOriginZoneAndFiredAtGreaterThanEqualOrderByIdAsc(originZone, escalatedAt)) {
            if (alert.wasDelivered()) {
                targets.add(alert.getTargetZone());
            }
        }
        return targets;
    }
}
