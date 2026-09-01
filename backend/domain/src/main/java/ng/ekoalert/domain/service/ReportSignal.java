package ng.ekoalert.domain.service;

import ng.ekoalert.engine.Severity;

import java.time.Instant;

/**
 * The three things quorum actually cares about in a report: who, how deep, when.
 *
 * <p>Quorum is evaluated over these rather than over entities so the same rule
 * runs against live reports and against a replayed historical event, with no
 * database on the replay path.
 */
public record ReportSignal(long reporterId, Severity level, Instant observedAt) {

    public static ReportSignal of(ng.ekoalert.domain.model.Report report) {
        return new ReportSignal(report.getReporterId(), report.getLevel(), report.getObservedAt());
    }
}
