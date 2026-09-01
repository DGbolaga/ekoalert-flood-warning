package ng.ekoalert.domain.service;

import ng.ekoalert.domain.model.Report;
import ng.ekoalert.domain.repo.ReportRepository;
import ng.ekoalert.engine.Severity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Decides whether a zone has escalated.
 *
 * <p>A zone escalates when two reports from two distinct verified, unsuspended
 * reporters land inside the quorum window, both at ANKLE or above. One report
 * never escalates. Two reports from the same person never escalate. A suspended
 * reporter's reports never count.
 *
 * <p>This lives in the domain and not in the engine. The engine walks a graph;
 * deciding whether anything happened in the first place is a question about
 * people and time.
 */
@Service
public class QuorumService {

    private final ReportRepository reports;
    private final AlertingProperties properties;

    public QuorumService(ReportRepository reports, AlertingProperties properties) {
        this.reports = reports;
        this.properties = properties;
    }

    /**
     * Evaluates the zone as of a newly filed report. The repository query is what
     * excludes unverified and suspended reporters.
     *
     * @return the level the zone should escalate to, or empty if there is no quorum
     */
    @Transactional(readOnly = true)
    public Optional<Severity> evaluate(Report trigger) {
        Instant from = trigger.getObservedAt().minus(properties.getQuorumWindow());
        Instant to = trigger.getObservedAt().plus(properties.getQuorumWindow());
        List<ReportSignal> countable = reports
                .findCountableInWindow(trigger.getZoneId(), from, to).stream()
                .map(ReportSignal::of)
                .toList();
        return quorumLevel(countable, trigger.getReporterId(), properties.getQuorumSize());
    }

    /**
     * Filters a zone's history down to the reports that count alongside the
     * trigger, then applies the quorum rule. Used by replay, which has a list of
     * reports and no database.
     *
     * <p>The window is centred on the triggering report, so every counted report
     * is within the window of it. For the default quorum of two that is exactly
     * the pairwise rule in the brief.
     */
    public static Optional<Severity> quorumLevel(List<ReportSignal> zoneHistory,
                                                 ReportSignal trigger,
                                                 Duration window,
                                                 int quorumSize) {
        List<ReportSignal> inWindow = zoneHistory.stream()
                .filter(signal -> !signal.observedAt().isBefore(trigger.observedAt().minus(window)))
                .filter(signal -> !signal.observedAt().isAfter(trigger.observedAt().plus(window)))
                .toList();
        return quorumLevel(inWindow, trigger.reporterId(), quorumSize);
    }

    /**
     * The level supported by at least {@code quorumSize} distinct reporters.
     *
     * <p>Each reporter contributes their most severe reading, the readings are
     * sorted descending, and the one at the quorum position is taken. For two
     * reporters that is the lower of the two levels, which is the conservative
     * choice the brief asks for; for more it generalises to the most severe level
     * that enough separate people actually saw.
     *
     * <p>The triggering reporter must be among them. A quorum is a statement
     * about the report just filed, not about the zone's whole history.
     */
    public static Optional<Severity> quorumLevel(List<ReportSignal> countable,
                                                 long triggerReporterId,
                                                 int quorumSize) {
        Map<Long, Severity> strongestPerReporter = new HashMap<>();
        for (ReportSignal signal : countable) {
            if (signal.level() == null) {
                continue;
            }
            strongestPerReporter.merge(signal.reporterId(), signal.level(),
                    (a, b) -> a.ordinal() >= b.ordinal() ? a : b);
        }

        if (!strongestPerReporter.containsKey(triggerReporterId)) {
            return Optional.empty();
        }
        if (strongestPerReporter.size() < quorumSize) {
            return Optional.empty();
        }

        List<Severity> descending = strongestPerReporter.values().stream()
                .sorted(Comparator.comparingInt(Severity::ordinal).reversed())
                .toList();
        return Optional.of(descending.get(quorumSize - 1));
    }
}
