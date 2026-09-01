package ng.ekoalert.domain.replay;

import ng.ekoalert.domain.service.AlertingProperties;
import ng.ekoalert.domain.service.GraphService;
import ng.ekoalert.domain.service.QuorumService;
import ng.ekoalert.domain.service.ReportSignal;
import ng.ekoalert.engine.DrainageGraph;
import ng.ekoalert.engine.Edge;
import ng.ekoalert.engine.PropagatedAlert;
import ng.ekoalert.engine.PropagationConfig;
import ng.ekoalert.engine.PropagationEngine;
import ng.ekoalert.engine.Severity;
import ng.ekoalert.engine.ZoneId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Runs a past flood event through the whole pipeline with delivery disabled.
 *
 * <p>This is the validation mechanism for the graph and the demo for the project
 * defence. It writes nothing and sends nothing, and it ignores the kill switch:
 * the switch governs what reaches real people, and nothing here does.
 *
 * <p>Reports supplied to a replay are taken as countable. Verification and
 * suspension are questions about the live roster, and a historical record has
 * already settled who was reporting.
 *
 * <p>The result is deterministic for a given request. Replay is only useful if
 * two runs of the same event agree.
 */
@Service
public class ReplayService {

    private final PropagationEngine engine;
    private final GraphService graphs;
    private final AlertingProperties defaults;

    public ReplayService(PropagationEngine engine, GraphService graphs, AlertingProperties defaults) {
        this.engine = engine;
        this.graphs = graphs;
        this.defaults = defaults;
    }

    /** Mutable per-zone bookkeeping for the duration of one replay. */
    private static final class ZoneState {
        final List<ReportSignal> history = new ArrayList<>();
        final Set<String> deliveredTargets = new LinkedHashSet<>();
        Severity activeLevel;
        Instant lastReportAt;

        boolean active() {
            return activeLevel != null;
        }
    }

    @Transactional(readOnly = true)
    public ReplayResult replay(ReplayRequest request) {
        DrainageGraph graph = resolveGraph(request);
        Settings settings = Settings.from(request.settings(), defaults);

        List<ReplayRequest.ReportSpec> ordered = request.reports() == null
                ? List.of()
                : request.reports().stream()
                .sorted(Comparator
                        .comparing(ReplayRequest.ReportSpec::observedAt)
                        .thenComparingLong(ReplayRequest.ReportSpec::reporterId)
                        .thenComparing(ReplayRequest.ReportSpec::zoneId))
                .toList();

        Map<String, ZoneState> states = new LinkedHashMap<>();
        List<ReplayResult.Escalation> escalations = new ArrayList<>();
        List<ReplayResult.PredictedAlert> alerts = new ArrayList<>();
        List<ReplayResult.AllClear> allClears = new ArrayList<>();

        for (ReplayRequest.ReportSpec spec : ordered) {
            sweepQuiet(states, spec.observedAt(), settings.deEscalationAfter(), allClears);

            ZoneState state = states.computeIfAbsent(spec.zoneId(), key -> new ZoneState());
            ReportSignal signal = new ReportSignal(spec.reporterId(), spec.level(), spec.observedAt());
            state.history.add(signal);
            state.lastReportAt = spec.observedAt();

            Optional<Severity> quorum = QuorumService.quorumLevel(
                    state.history, signal, settings.quorumWindow(), settings.quorumSize());
            if (quorum.isEmpty()) {
                continue;
            }
            Severity level = quorum.get();
            if (state.active() && level.ordinal() <= state.activeLevel.ordinal()) {
                continue;
            }

            state.activeLevel = level;
            List<ReplayResult.PredictedAlert> produced =
                    propagate(graph, spec.zoneId(), level, spec.observedAt(), settings);
            produced.stream()
                    .filter(ReplayResult.PredictedAlert::wouldDeliver)
                    .forEach(alert -> state.deliveredTargets.add(alert.targetZone()));

            alerts.addAll(produced);
            escalations.add(new ReplayResult.Escalation(spec.zoneId(), level, spec.observedAt(), produced.size()));
        }

        // Everything still alerting when the record runs out clears on schedule.
        sweepQuiet(states, Instant.MAX, settings.deEscalationAfter(), allClears);

        return new ReplayResult(
                List.copyOf(escalations),
                List.copyOf(alerts),
                List.copyOf(allClears),
                summarise(ordered, escalations, alerts));
    }

    private void sweepQuiet(Map<String, ZoneState> states,
                            Instant now,
                            Duration quietFor,
                            List<ReplayResult.AllClear> allClears) {
        for (Map.Entry<String, ZoneState> entry : states.entrySet()) {
            ZoneState state = entry.getValue();
            if (!state.active()) {
                continue;
            }
            Instant clearsAt = state.lastReportAt.plus(quietFor);
            if (now != Instant.MAX && now.isBefore(clearsAt)) {
                continue;
            }
            for (String target : state.deliveredTargets) {
                allClears.add(new ReplayResult.AllClear(entry.getKey(), target, clearsAt));
            }
            state.activeLevel = null;
            state.deliveredTargets.clear();
        }
    }

    private List<ReplayResult.PredictedAlert> propagate(DrainageGraph graph,
                                                        String originZone,
                                                        Severity level,
                                                        Instant firedAt,
                                                        Settings settings) {
        List<PropagatedAlert> propagated = engine.propagate(
                graph,
                new ZoneId(originZone),
                level,
                new PropagationConfig(settings.maxHops(), settings.requireConfirmedEdges()));

        List<ReplayResult.PredictedAlert> predicted = new ArrayList<>(propagated.size());
        for (PropagatedAlert alert : propagated) {
            predicted.add(new ReplayResult.PredictedAlert(
                    originZone,
                    alert.target().value(),
                    alert.level(),
                    alert.etaMinutes(),
                    alert.hops(),
                    firedAt,
                    firedAt.plus(Duration.ofMinutes(alert.etaMinutes())),
                    alert.pathConfirmed()));
        }
        return predicted;
    }

    private DrainageGraph resolveGraph(ReplayRequest request) {
        if (request.edges() == null || request.edges().isEmpty()) {
            return graphs.snapshot();
        }
        List<Edge> edges = request.edges().stream()
                .map(spec -> new Edge(
                        new ZoneId(spec.from()),
                        new ZoneId(spec.to()),
                        spec.travelMinutes(),
                        spec.confidence(),
                        spec.blocked()))
                .toList();
        return new DrainageGraph(edges);
    }

    private ReplayResult.Summary summarise(List<ReplayRequest.ReportSpec> ordered,
                                           List<ReplayResult.Escalation> escalations,
                                           List<ReplayResult.PredictedAlert> alerts) {
        long deliverable = alerts.stream().filter(ReplayResult.PredictedAlert::wouldDeliver).count();
        return new ReplayResult.Summary(
                ordered.size(),
                (int) escalations.stream().map(ReplayResult.Escalation::zoneId).distinct().count(),
                alerts.size(),
                (int) deliverable,
                alerts.size() - (int) deliverable,
                ordered.isEmpty() ? null : ordered.get(0).observedAt(),
                ordered.isEmpty() ? null : ordered.get(ordered.size() - 1).observedAt());
    }

    /** The settings actually in force, after overrides are folded onto the live defaults. */
    private record Settings(int maxHops,
                            boolean requireConfirmedEdges,
                            Duration quorumWindow,
                            int quorumSize,
                            Duration deEscalationAfter) {

        static Settings from(ReplayRequest.Settings overrides, AlertingProperties defaults) {
            if (overrides == null) {
                return new Settings(defaults.getMaxHops(), defaults.isRequireConfirmedEdges(),
                        defaults.getQuorumWindow(), defaults.getQuorumSize(), defaults.getDeEscalationAfter());
            }
            return new Settings(
                    overrides.maxHops() != null ? overrides.maxHops() : defaults.getMaxHops(),
                    overrides.requireConfirmedEdges() != null
                            ? overrides.requireConfirmedEdges() : defaults.isRequireConfirmedEdges(),
                    overrides.quorumWindow() != null ? overrides.quorumWindow() : defaults.getQuorumWindow(),
                    overrides.quorumSize() != null ? overrides.quorumSize() : defaults.getQuorumSize(),
                    overrides.deEscalationAfter() != null
                            ? overrides.deEscalationAfter() : defaults.getDeEscalationAfter());
        }
    }
}
