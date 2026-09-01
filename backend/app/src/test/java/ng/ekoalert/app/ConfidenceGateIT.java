package ng.ekoalert.app;

import ng.ekoalert.domain.model.Alert;
import ng.ekoalert.domain.model.DrainageEdge;
import ng.ekoalert.domain.model.Reporter;
import ng.ekoalert.domain.service.EdgeCorrectionService;
import ng.ekoalert.domain.service.ReportService;
import ng.ekoalert.engine.Confidence;
import ng.ekoalert.engine.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The confidence rule end to end: a full map that sends almost nothing, getting
 * progressively louder as residents confirm edges. That transition is the demo,
 * so it gets its own test.
 */
class ConfidenceGateIT extends IntegrationTestBase {

    @Autowired ReportService reportService;
    @Autowired EdgeCorrectionService correctionService;

    private Reporter ada;
    private Reporter bola;
    private DrainageEdge firstHop;
    private DrainageEdge secondHop;

    @BeforeEach
    void graph() {
        zone("Z01");
        zone("Z02");
        zone("Z03");
        firstHop = edge("Z01", "Z02", 16, Confidence.INFERRED);
        secondHop = edge("Z02", "Z03", 17, Confidence.INFERRED);

        ada = verifiedReporter("ada", "Z01");
        bola = verifiedReporter("bola", "Z01");
    }

    private void escalateZone01(int minuteOffset) {
        reportService.file(ada.getId(), "Z01", Severity.IMPASSABLE, null,
                NOON.plus(Duration.ofMinutes(minuteOffset)));
        reportService.file(bola.getId(), "Z01", Severity.IMPASSABLE, null,
                NOON.plus(Duration.ofMinutes(minuteOffset + 5)));
    }

    @Test
    @DisplayName("with every edge inferred, an escalation writes the rows and delivers nothing")
    void inferredGraphDeliversNothing() {
        escalateZone01(0);

        assertThat(alerts.findAll()).hasSize(2);
        assertThat(alerts.findAll()).allSatisfy(alert ->
                assertThat(alert.getSuppressedBy()).isEqualTo(Alert.INFERRED_EDGE));
        assertThat(published.fired).isEmpty();
    }

    @Test
    @DisplayName("confirming the path turns the same escalation loud")
    void confirmingThePathDelivers() {
        confirmToThreshold(firstHop);
        confirmToThreshold(secondHop);

        escalateZone01(0);

        assertThat(alerts.findAll()).hasSize(2);
        assertThat(alerts.findAll()).allSatisfy(alert ->
                assertThat(alert.getSuppressedBy()).isNull());
        assertThat(published.fired)
                .extracting(RecordingAlertPublisher.Fired::targetZone)
                .containsExactly("Z02", "Z03");
    }

    @Test
    @DisplayName("confirming only the first hop delivers only the first hop")
    void partialConfirmationDeliversPartially() {
        confirmToThreshold(firstHop);

        escalateZone01(0);

        assertThat(alerts.findAll())
                .extracting(Alert::getTargetZone, Alert::getSuppressedBy)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Z02", null),
                        org.assertj.core.groups.Tuple.tuple("Z03", Alert.INFERRED_EDGE));
        assertThat(published.fired)
                .extracting(RecordingAlertPublisher.Fired::targetZone)
                .containsExactly("Z02");
    }

    @Test
    @DisplayName("one vote does not confirm an edge")
    void oneVoteIsNotEnough() {
        correctionService.confirm(firstHop.getId(), ada.getId(), NOON);

        assertThat(edges.findById(firstHop.getId())).get()
                .satisfies(edge -> assertThat(edge.getConfidence()).isEqualTo(Confidence.INFERRED));
        // Logged all the same. The trail is the point.
        assertThat(corrections.findByEdgeIdOrderByIdAsc(firstHop.getId())).hasSize(1);
    }

    @Test
    @DisplayName("the same resident tapping twice does not confirm an edge")
    void oneResidentTwiceIsNotEnough() {
        correctionService.confirm(firstHop.getId(), ada.getId(), NOON);
        correctionService.confirm(firstHop.getId(), ada.getId(), NOON.plus(Duration.ofMinutes(1)));

        assertThat(edges.findById(firstHop.getId())).get()
                .satisfies(edge -> assertThat(edge.getConfidence()).isEqualTo(Confidence.INFERRED));
        assertThat(corrections.findByEdgeIdOrderByIdAsc(firstHop.getId())).hasSize(2);
    }

    @Test
    @DisplayName("two distinct residents confirm an edge, and the tap that crossed says so")
    void twoResidentsConfirm() {
        assertThat(correctionService.confirm(firstHop.getId(), ada.getId(), NOON).thresholdMet()).isFalse();
        assertThat(correctionService.confirm(firstHop.getId(), bola.getId(), NOON).thresholdMet()).isTrue();

        assertThat(edges.findById(firstHop.getId())).get()
                .satisfies(edge -> assertThat(edge.getConfidence()).isEqualTo(Confidence.CONFIRMED));
    }

    @Test
    @DisplayName("a rejected edge stops carrying and stops being alertable")
    void rejectionStopsPropagation() {
        confirmToThreshold(firstHop);
        correctionService.reject(firstHop.getId(), ada.getId(), NOON);
        correctionService.reject(firstHop.getId(), bola.getId(), NOON);

        assertThat(edges.findById(firstHop.getId())).get()
                .satisfies(edge -> assertThat(edge.getConfidence()).isEqualTo(Confidence.REJECTED));

        escalateZone01(0);

        assertThat(alerts.count()).isZero();
    }

    @Test
    @DisplayName("residents propose the junction edge inference left blank, and it arrives inferred")
    void proposedEdgeIsCreatedInferred() {
        zone("Z09");

        assertThat(correctionService.propose("Z03", "Z09", ada.getId(), NOON).edge()).isNull();
        var second = correctionService.propose("Z03", "Z09", bola.getId(), NOON);

        assertThat(second.thresholdMet()).isTrue();
        assertThat(second.edge()).isNotNull();
        // Proposing is not confirming.
        assertThat(second.edge().getConfidence()).isEqualTo(Confidence.INFERRED);
        assertThat(second.edge().getTravelMinutes()).isPositive();
        assertThat(second.edge().getInferenceBasis()).contains("resident proposal");
    }

    @Test
    @DisplayName("every correction is logged with who and when, whatever it changed")
    void correctionsAreLogged() {
        correctionService.confirm(firstHop.getId(), ada.getId(), NOON);
        correctionService.reject(secondHop.getId(), bola.getId(), NOON.plus(Duration.ofMinutes(1)));

        assertThat(corrections.findAll()).hasSize(2);
        assertThat(corrections.findAll()).allSatisfy(correction -> {
            assertThat(correction.getReporterId()).isNotNull();
            assertThat(correction.getCreatedAt()).isNotNull();
            assertThat(correction.getFromZone()).isNotBlank();
        });
    }

    private void confirmToThreshold(DrainageEdge edge) {
        correctionService.confirm(edge.getId(), ada.getId(), NOON);
        correctionService.confirm(edge.getId(), bola.getId(), NOON);
    }
}
