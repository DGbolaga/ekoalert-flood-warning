package ng.ekoalert.domain.service;

import ng.ekoalert.domain.model.DrainageEdge;
import ng.ekoalert.domain.model.Report;
import ng.ekoalert.domain.model.Reporter;
import ng.ekoalert.domain.model.ZoneStatus;
import ng.ekoalert.domain.repo.DrainageEdgeRepository;
import ng.ekoalert.domain.repo.ReportRepository;
import ng.ekoalert.domain.repo.ReporterRepository;
import ng.ekoalert.domain.repo.ZoneRepository;
import ng.ekoalert.domain.repo.ZoneStatusRepository;
import ng.ekoalert.engine.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/** Files a report, then asks whether it changed anything. */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private final ReportRepository reports;
    private final ReporterRepository reporters;
    private final ZoneRepository zones;
    private final ZoneStatusRepository zoneStatuses;
    private final DrainageEdgeRepository edges;
    private final QuorumService quorum;
    private final EscalationService escalation;

    public ReportService(ReportRepository reports,
                         ReporterRepository reporters,
                         ZoneRepository zones,
                         ZoneStatusRepository zoneStatuses,
                         DrainageEdgeRepository edges,
                         QuorumService quorum,
                         EscalationService escalation) {
        this.reports = reports;
        this.reporters = reporters;
        this.zones = zones;
        this.zoneStatuses = zoneStatuses;
        this.edges = edges;
        this.quorum = quorum;
        this.escalation = escalation;
    }

    /**
     * @param zoneId       optional. A reporter is vetted for one zone, so this
     *                     must match theirs if supplied.
     * @param drainBlocked optional one-tap field. Null means the reporter said
     *                     nothing about the drain and nothing is changed.
     */
    @Transactional
    public ReportOutcome file(long reporterId,
                              String zoneId,
                              Severity level,
                              Boolean drainBlocked,
                              Instant observedAt) {
        Reporter reporter = reporters.findById(reporterId)
                .orElseThrow(() -> new IllegalArgumentException("unknown reporter: " + reporterId));

        String zone = zoneId != null ? zoneId : reporter.getZoneId();
        if (!zone.equals(reporter.getZoneId())) {
            throw new IllegalArgumentException(
                    "reporter " + reporterId + " is vetted for " + reporter.getZoneId() + ", not " + zone);
        }
        if (!zones.existsById(zone)) {
            throw new IllegalArgumentException("unknown zone: " + zone);
        }

        Report report = reports.save(new Report(zone, reporterId, level, drainBlocked, observedAt));

        if (drainBlocked != null) {
            applyBlockage(zone, drainBlocked);
        }

        // Stored either way, so the audit trail is complete, but never counted.
        if (!reporter.countsTowardQuorum()) {
            log.info("report {} from reporter {} stored but not counted: verified={} suspended={}",
                    report.getId(), reporterId, reporter.getVerifiedAt() != null, reporter.isSuspended());
            return new ReportOutcome(report, Optional.empty(), Optional.empty());
        }

        Optional<Severity> quorumLevel = quorum.evaluate(report);
        if (quorumLevel.isEmpty()) {
            return new ReportOutcome(report, Optional.empty(), Optional.empty());
        }

        Severity level_ = quorumLevel.get();
        if (!worthEscalating(zone, level_)) {
            return new ReportOutcome(report, quorumLevel, Optional.empty());
        }

        return new ReportOutcome(report, quorumLevel,
                Optional.of(escalation.escalate(zone, level_, observedAt)));
    }

    /**
     * A blocked drain in a zone means water is not leaving it, so the zone's
     * outbound edges stop carrying. Reported clear, they carry again. One report
     * is enough in either direction: this is an observation about a physical
     * object in front of the reporter, not a claim about where water goes.
     */
    private void applyBlockage(String zone, boolean blocked) {
        for (DrainageEdge edge : edges.findByFromZoneOrderByIdAsc(zone)) {
            if (edge.isBlocked() != blocked) {
                edge.setBlocked(blocked);
                edges.save(edge);
                log.info("edge {} to {} marked {}", edge.getFromZone(), edge.getToZone(),
                        blocked ? "blocked" : "clear");
            }
        }
    }

    /**
     * A zone already alerting is not re-propagated unless the water got worse.
     * Re-firing the same alert every time another neighbour reports would train
     * people to ignore it.
     */
    private boolean worthEscalating(String zone, Severity level) {
        Optional<ZoneStatus> status = zoneStatuses.findById(zone);
        if (status.isEmpty() || !status.get().isActive()) {
            return true;
        }
        Severity current = status.get().getLevel();
        return current == null || level.ordinal() > current.ordinal();
    }
}
