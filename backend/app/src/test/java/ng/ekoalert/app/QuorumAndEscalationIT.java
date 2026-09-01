package ng.ekoalert.app;

import ng.ekoalert.domain.model.Alert;
import ng.ekoalert.domain.model.Reporter;
import ng.ekoalert.domain.service.DeEscalationService;
import ng.ekoalert.domain.service.KillSwitchService;
import ng.ekoalert.domain.service.ReportOutcome;
import ng.ekoalert.domain.service.ReportService;
import ng.ekoalert.engine.Confidence;
import ng.ekoalert.engine.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Layer 3. Quorum, escalation, de-escalation and the kill switch against a real
 * database and the real migrations.
 *
 * <p>The graph is a confirmed three zone chain, so anything that fails to
 * deliver here failed for the reason under test and not because an edge was
 * still inferred.
 */
class QuorumAndEscalationIT extends IntegrationTestBase {

    @Autowired ReportService reportService;
    @Autowired DeEscalationService deEscalation;
    @Autowired KillSwitchService killSwitch;

    private Reporter ada;
    private Reporter bola;

    @BeforeEach
    void graph() {
        zone("Z01");
        zone("Z02");
        zone("Z03");
        edge("Z01", "Z02", 16, Confidence.CONFIRMED);
        edge("Z02", "Z03", 17, Confidence.CONFIRMED);

        ada = verifiedReporter("ada", "Z01");
        bola = verifiedReporter("bola", "Z01");
    }

    @Test
    @DisplayName("one report into a zone does not escalate it")
    void oneReportDoesNotEscalate() {
        ReportOutcome outcome = reportService.file(
                ada.getId(), "Z01", Severity.IMPASSABLE, null, NOON);

        assertThat(outcome.escalation()).isEmpty();
        assertThat(zoneStatuses.findById("Z01")).isEmpty();
        assertThat(alerts.count()).isZero();
        assertThat(published.fired).isEmpty();
    }

    @Test
    @DisplayName("two reports into one zone escalate it")
    void twoReportsEscalate() {
        reportService.file(ada.getId(), "Z01", Severity.IMPASSABLE, null, NOON);
        ReportOutcome second = reportService.file(
                bola.getId(), "Z01", Severity.IMPASSABLE, null, NOON.plus(Duration.ofMinutes(10)));

        assertThat(second.escalation()).isPresent();
        assertThat(zoneStatuses.findById("Z01")).get()
                .satisfies(status -> {
                    assertThat(status.isActive()).isTrue();
                    assertThat(status.getLevel()).isEqualTo(Severity.IMPASSABLE);
                });

        assertThat(alerts.findAll())
                .extracting(Alert::getTargetZone, Alert::getLevel, Alert::getEtaMinutes, Alert::getHops)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Z02", Severity.KNEE, 16, 1),
                        org.assertj.core.groups.Tuple.tuple("Z03", Severity.ANKLE, 33, 2));
        assertThat(published.fired).hasSize(2);
    }

    @Test
    @DisplayName("two reports from the same reporter never escalate")
    void sameReporterTwiceDoesNotEscalate() {
        reportService.file(ada.getId(), "Z01", Severity.IMPASSABLE, null, NOON);
        ReportOutcome second = reportService.file(
                ada.getId(), "Z01", Severity.IMPASSABLE, null, NOON.plus(Duration.ofMinutes(5)));

        assertThat(second.escalation()).isEmpty();
        assertThat(alerts.count()).isZero();
    }

    @Test
    @DisplayName("a suspended reporter's report is stored and never counted")
    void suspendedReporterNeverCounts() {
        bola.setSuspended(true);
        reporters.save(bola);

        reportService.file(ada.getId(), "Z01", Severity.IMPASSABLE, null, NOON);
        ReportOutcome second = reportService.file(
                bola.getId(), "Z01", Severity.IMPASSABLE, null, NOON.plus(Duration.ofMinutes(5)));

        assertThat(second.escalation()).isEmpty();
        assertThat(second.quorumLevel()).isEmpty();
        // Stored, because the audit trail has to be complete.
        assertThat(reports.count()).isEqualTo(2);
        assertThat(alerts.count()).isZero();
    }

    @Test
    @DisplayName("an unverified reporter's report is stored and never counted")
    void unverifiedReporterNeverCounts() {
        Reporter chidi = unverifiedReporter("chidi", "Z01");

        reportService.file(ada.getId(), "Z01", Severity.IMPASSABLE, null, NOON);
        ReportOutcome second = reportService.file(
                chidi.getId(), "Z01", Severity.IMPASSABLE, null, NOON.plus(Duration.ofMinutes(5)));

        assertThat(second.escalation()).isEmpty();
        assertThat(reports.count()).isEqualTo(2);
        assertThat(alerts.count()).isZero();
    }

    @Test
    @DisplayName("reports more than the quorum window apart do not corroborate")
    void outsideTheWindowDoesNotEscalate() {
        reportService.file(ada.getId(), "Z01", Severity.IMPASSABLE, null, NOON);
        ReportOutcome second = reportService.file(
                bola.getId(), "Z01", Severity.IMPASSABLE, null, NOON.plus(Duration.ofMinutes(46)));

        assertThat(second.escalation()).isEmpty();
    }

    @Test
    @DisplayName("the zone escalates at the lower of the two levels")
    void escalatesConservatively() {
        reportService.file(ada.getId(), "Z01", Severity.IMPASSABLE, null, NOON);
        reportService.file(bola.getId(), "Z01", Severity.KNEE, null, NOON.plus(Duration.ofMinutes(5)));

        assertThat(zoneStatuses.findById("Z01")).get()
                .extracting(status -> status.getLevel())
                .isEqualTo(Severity.KNEE);
    }

    @Test
    @DisplayName("with the kill switch off every alert row is written and marked, and nothing is delivered")
    void killSwitchSuppressesEverything() {
        killSwitch.setAlertsEnabled(false);

        reportService.file(ada.getId(), "Z01", Severity.IMPASSABLE, null, NOON);
        reportService.file(bola.getId(), "Z01", Severity.IMPASSABLE, null, NOON.plus(Duration.ofMinutes(5)));

        List<Alert> written = alerts.findAll();
        assertThat(written).hasSize(2);
        assertThat(written).allSatisfy(alert ->
                assertThat(alert.getSuppressedBy()).isEqualTo(Alert.KILL_SWITCH));
        assertThat(published.fired).isEmpty();
        assertThat(deliveries.count()).isZero();
    }

    @Test
    @DisplayName("the kill switch takes precedence over an unconfirmed path in the record")
    void killSwitchOutranksInferredEdge() {
        edges.findByFromZoneAndToZone("Z01", "Z02").ifPresent(edge -> {
            edge.setConfidence(Confidence.INFERRED);
            edges.save(edge);
        });
        killSwitch.setAlertsEnabled(false);

        reportService.file(ada.getId(), "Z01", Severity.IMPASSABLE, null, NOON);
        reportService.file(bola.getId(), "Z01", Severity.IMPASSABLE, null, NOON.plus(Duration.ofMinutes(5)));

        assertThat(alerts.findAll()).allSatisfy(alert ->
                assertThat(alert.getSuppressedBy()).isEqualTo(Alert.KILL_SWITCH));
    }

    @Test
    @DisplayName("a zone goes quiet, clears, and the all-clear reaches everyone who heard the alert")
    void deEscalationSendsTheAllClear() {
        reportService.file(ada.getId(), "Z01", Severity.IMPASSABLE, null, NOON);
        reportService.file(bola.getId(), "Z01", Severity.IMPASSABLE, null, NOON.plus(Duration.ofMinutes(5)));
        published.reset();

        // Still inside the quiet window: nothing clears yet.
        assertThat(deEscalation.sweep(NOON.plus(Duration.ofMinutes(90)))).isEmpty();

        List<String> cleared = deEscalation.sweep(NOON.plus(Duration.ofMinutes(96)));

        assertThat(cleared).containsExactly("Z01");
        assertThat(zoneStatuses.findById("Z01")).get()
                .satisfies(status -> assertThat(status.isActive()).isFalse());
        assertThat(published.allClears)
                .extracting(RecordingAlertPublisher.Cleared::targetZone)
                .containsExactly("Z02", "Z03");
    }

    @Test
    @DisplayName("a suppressed alert reached nobody, so nobody gets an all-clear for it")
    void noAllClearForSuppressedAlerts() {
        killSwitch.setAlertsEnabled(false);
        reportService.file(ada.getId(), "Z01", Severity.IMPASSABLE, null, NOON);
        reportService.file(bola.getId(), "Z01", Severity.IMPASSABLE, null, NOON.plus(Duration.ofMinutes(5)));
        published.reset();

        deEscalation.sweep(NOON.plus(Duration.ofMinutes(96)));

        assertThat(published.allClears).isEmpty();
    }

    @Test
    @DisplayName("a zone already alerting is not re-propagated unless the water got worse")
    void reEscalationOnlyOnAWorseLevel() {
        reportService.file(ada.getId(), "Z01", Severity.KNEE, null, NOON);
        reportService.file(bola.getId(), "Z01", Severity.KNEE, null, NOON.plus(Duration.ofMinutes(5)));
        long afterFirst = alerts.count();

        // Same level again: no new alerts.
        reportService.file(ada.getId(), "Z01", Severity.KNEE, null, NOON.plus(Duration.ofMinutes(10)));
        reportService.file(bola.getId(), "Z01", Severity.KNEE, null, NOON.plus(Duration.ofMinutes(12)));
        assertThat(alerts.count()).isEqualTo(afterFirst);

        // Worse: propagate again.
        reportService.file(ada.getId(), "Z01", Severity.IMPASSABLE, null, NOON.plus(Duration.ofMinutes(20)));
        reportService.file(bola.getId(), "Z01", Severity.IMPASSABLE, null, NOON.plus(Duration.ofMinutes(22)));
        assertThat(alerts.count()).isGreaterThan(afterFirst);
    }

    @Test
    @DisplayName("a reported blockage stops the zone's outbound edges carrying")
    void drainBlockedStopsPropagation() {
        reportService.file(ada.getId(), "Z01", Severity.IMPASSABLE, true, NOON);
        reportService.file(bola.getId(), "Z01", Severity.IMPASSABLE, null, NOON.plus(Duration.ofMinutes(5)));

        assertThat(edges.findByFromZoneAndToZone("Z01", "Z02")).get()
                .satisfies(edge -> assertThat(edge.isBlocked()).isTrue());
        assertThat(alerts.count()).isZero();
        assertThat(published.fired).isEmpty();
    }
}
