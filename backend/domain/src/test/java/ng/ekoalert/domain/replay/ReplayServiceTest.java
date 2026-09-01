package ng.ekoalert.domain.replay;

import ng.ekoalert.domain.service.AlertingProperties;
import ng.ekoalert.domain.service.GraphService;
import ng.ekoalert.engine.BestFirstPropagationEngine;
import ng.ekoalert.engine.Confidence;
import ng.ekoalert.engine.DrainageGraph;
import ng.ekoalert.engine.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Replay with no database. The service only reaches for the stored graph when
 * the request does not carry one, and every test here carries one.
 */
class ReplayServiceTest {

    private static final Instant NOON = Instant.parse("2026-06-15T12:00:00Z");

    /** Loudly refuses the database path, so a test that drifts onto it fails rather than passes quietly. */
    private final GraphService graphs = new GraphService(null) {
        @Override
        public DrainageGraph snapshot() {
            throw new AssertionError("these tests supply their own graph");
        }
    };

    private final ReplayService replay =
            new ReplayService(new BestFirstPropagationEngine(), graphs, new AlertingProperties());

    private static ReplayRequest.EdgeSpec edge(String from, String to, int minutes, Confidence confidence) {
        return new ReplayRequest.EdgeSpec(from, to, minutes, confidence, false);
    }

    private static ReplayRequest.ReportSpec report(String zone, long reporter, Severity level, int minute) {
        return new ReplayRequest.ReportSpec(zone, reporter, level, NOON.plus(Duration.ofMinutes(minute)));
    }

    private static final List<ReplayRequest.EdgeSpec> CHAIN = List.of(
            edge("Z01", "Z02", 16, Confidence.CONFIRMED),
            edge("Z02", "Z03", 17, Confidence.CONFIRMED),
            edge("Z03", "Z04", 17, Confidence.CONFIRMED));

    @Test
    @DisplayName("an empty event replays to nothing")
    void emptyEvent() {
        ReplayResult result = replay.replay(new ReplayRequest(CHAIN, List.of(), null));

        assertThat(result.escalations()).isEmpty();
        assertThat(result.alerts()).isEmpty();
        assertThat(result.summary().reportsReplayed()).isZero();
    }

    @Test
    @DisplayName("one report escalates nothing")
    void oneReportEscalatesNothing() {
        ReplayResult result = replay.replay(new ReplayRequest(
                CHAIN, List.of(report("Z01", 1, Severity.IMPASSABLE, 0)), null));

        assertThat(result.escalations()).isEmpty();
        assertThat(result.alerts()).isEmpty();
    }

    @Test
    @DisplayName("two reporters escalate, and the predicted arrival is the report time plus the ETA")
    void twoReportersEscalate() {
        ReplayResult result = replay.replay(new ReplayRequest(CHAIN, List.of(
                report("Z01", 1, Severity.IMPASSABLE, 0),
                report("Z01", 2, Severity.IMPASSABLE, 10)), null));

        assertThat(result.escalations()).singleElement().satisfies(escalation -> {
            assertThat(escalation.zoneId()).isEqualTo("Z01");
            assertThat(escalation.level()).isEqualTo(Severity.IMPASSABLE);
            assertThat(escalation.at()).isEqualTo(NOON.plus(Duration.ofMinutes(10)));
        });

        assertThat(result.alerts()).extracting(
                        ReplayResult.PredictedAlert::targetZone,
                        ReplayResult.PredictedAlert::level,
                        ReplayResult.PredictedAlert::etaMinutes,
                        ReplayResult.PredictedAlert::expectedArrival)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Z02", Severity.KNEE, 16,
                                NOON.plus(Duration.ofMinutes(26))),
                        org.assertj.core.groups.Tuple.tuple("Z03", Severity.ANKLE, 33,
                                NOON.plus(Duration.ofMinutes(43))));
    }

    @Test
    @DisplayName("an unconfirmed edge anywhere on the path means the alert would not have gone out")
    void unconfirmedPathIsNotDeliverable() {
        List<ReplayRequest.EdgeSpec> partlyInferred = List.of(
                edge("Z01", "Z02", 16, Confidence.INFERRED),
                edge("Z02", "Z03", 17, Confidence.CONFIRMED));

        ReplayResult result = replay.replay(new ReplayRequest(partlyInferred, List.of(
                report("Z01", 1, Severity.IMPASSABLE, 0),
                report("Z01", 2, Severity.IMPASSABLE, 10)), null));

        assertThat(result.alerts()).isNotEmpty();
        assertThat(result.alerts()).noneMatch(ReplayResult.PredictedAlert::wouldDeliver);
        assertThat(result.summary().alertsDeliverable()).isZero();
        assertThat(result.summary().suppressedByUnconfirmedPath()).isEqualTo(result.alerts().size());
    }

    @Test
    @DisplayName("confirming the same graph turns the same event loud, which is the whole demo")
    void confirmingTheGraphChangesTheOutcome() {
        List<ReplayRequest.ReportSpec> reports = List.of(
                report("Z01", 1, Severity.IMPASSABLE, 0),
                report("Z01", 2, Severity.IMPASSABLE, 10));

        ReplayResult inferred = replay.replay(new ReplayRequest(List.of(
                edge("Z01", "Z02", 16, Confidence.INFERRED)), reports, null));
        ReplayResult confirmed = replay.replay(new ReplayRequest(List.of(
                edge("Z01", "Z02", 16, Confidence.CONFIRMED)), reports, null));

        assertThat(inferred.summary().alertsDeliverable()).isZero();
        assertThat(confirmed.summary().alertsDeliverable()).isEqualTo(1);
        assertThat(inferred.summary().alertsPredicted())
                .isEqualTo(confirmed.summary().alertsPredicted());
    }

    @Test
    @DisplayName("the all-clear fires the de-escalation window after the last report")
    void allClearAfterTheQuietWindow() {
        ReplayResult result = replay.replay(new ReplayRequest(CHAIN, List.of(
                report("Z01", 1, Severity.IMPASSABLE, 0),
                report("Z01", 2, Severity.IMPASSABLE, 10)), null));

        assertThat(result.allClears())
                .extracting(ReplayResult.AllClear::targetZone, ReplayResult.AllClear::at)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Z02", NOON.plus(Duration.ofMinutes(100))),
                        org.assertj.core.groups.Tuple.tuple("Z03", NOON.plus(Duration.ofMinutes(100))));
    }

    @Test
    @DisplayName("nothing that was never delivered gets an all-clear")
    void noAllClearForSuppressedAlerts() {
        ReplayResult result = replay.replay(new ReplayRequest(List.of(
                edge("Z01", "Z02", 16, Confidence.INFERRED)), List.of(
                report("Z01", 1, Severity.IMPASSABLE, 0),
                report("Z01", 2, Severity.IMPASSABLE, 10)), null));

        assertThat(result.allClears()).isEmpty();
    }

    @Test
    @DisplayName("reports arriving out of order are replayed in time order")
    void reportsAreSorted() {
        ReplayResult shuffled = replay.replay(new ReplayRequest(CHAIN, List.of(
                report("Z01", 2, Severity.IMPASSABLE, 10),
                report("Z01", 1, Severity.IMPASSABLE, 0)), null));

        assertThat(shuffled.escalations()).singleElement()
                .extracting(ReplayResult.Escalation::at)
                .isEqualTo(NOON.plus(Duration.ofMinutes(10)));
    }

    @Test
    @DisplayName("the same request replays to the same result")
    void deterministic() {
        ReplayRequest request = new ReplayRequest(CHAIN, List.of(
                report("Z03", 3, Severity.IMPASSABLE, 40),
                report("Z01", 1, Severity.IMPASSABLE, 0),
                report("Z01", 2, Severity.KNEE, 10),
                report("Z03", 4, Severity.KNEE, 45)), null);

        assertThat(replay.replay(request)).isEqualTo(replay.replay(request));
    }

    @Test
    @DisplayName("a zone that clears can escalate again later in the same event")
    void reEscalationAfterClearing() {
        ReplayResult result = replay.replay(new ReplayRequest(CHAIN, List.of(
                report("Z01", 1, Severity.IMPASSABLE, 0),
                report("Z01", 2, Severity.IMPASSABLE, 10),
                // Two hours later, past the ninety minute quiet window.
                report("Z01", 1, Severity.IMPASSABLE, 130),
                report("Z01", 2, Severity.IMPASSABLE, 135)), null));

        assertThat(result.escalations()).hasSize(2);
        assertThat(result.allClears()).isNotEmpty();
    }

    @Test
    @DisplayName("settings on the request override the live defaults")
    void settingsOverride() {
        ReplayRequest.Settings oneHop = new ReplayRequest.Settings(1, null, null, null, null);

        ReplayResult result = replay.replay(new ReplayRequest(CHAIN, List.of(
                report("Z01", 1, Severity.IMPASSABLE, 0),
                report("Z01", 2, Severity.IMPASSABLE, 10)), oneHop));

        assertThat(result.alerts()).extracting(ReplayResult.PredictedAlert::targetZone)
                .containsExactly("Z02");
    }
}
