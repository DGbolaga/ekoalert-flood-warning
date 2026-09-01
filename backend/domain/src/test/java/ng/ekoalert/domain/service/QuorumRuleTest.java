package ng.ekoalert.domain.service;

import ng.ekoalert.engine.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The quorum rule on its own, with no database. The repository query is what
 * excludes suspended and unverified reporters, so those cases belong to the
 * integration layer; everything about counting people and levels lives here.
 */
class QuorumRuleTest {

    private static final Instant NOON = Instant.parse("2026-06-15T12:00:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(45);

    private static ReportSignal signal(long reporterId, Severity level, int minutesAfterNoon) {
        return new ReportSignal(reporterId, level, NOON.plus(Duration.ofMinutes(minutesAfterNoon)));
    }

    @Test
    @DisplayName("one report never escalates")
    void oneReportIsNotEnough() {
        ReportSignal only = signal(1, Severity.IMPASSABLE, 0);

        assertThat(QuorumService.quorumLevel(List.of(only), only, WINDOW, 2)).isEmpty();
    }

    @Test
    @DisplayName("two reports from the same reporter never escalate")
    void sameReporterTwiceIsNotEnough() {
        ReportSignal first = signal(1, Severity.IMPASSABLE, 0);
        ReportSignal second = signal(1, Severity.IMPASSABLE, 10);

        assertThat(QuorumService.quorumLevel(List.of(first, second), second, WINDOW, 2)).isEmpty();
    }

    @Test
    @DisplayName("two distinct reporters inside the window escalate at the lower of the two levels")
    void twoReportersEscalateConservatively() {
        ReportSignal ada = signal(1, Severity.IMPASSABLE, 0);
        ReportSignal bola = signal(2, Severity.KNEE, 20);

        assertThat(QuorumService.quorumLevel(List.of(ada, bola), bola, WINDOW, 2))
                .contains(Severity.KNEE);
    }

    @Test
    @DisplayName("the lower level wins whichever reporter arrived second")
    void orderDoesNotChangeTheLevel() {
        ReportSignal ada = signal(1, Severity.ANKLE, 0);
        ReportSignal bola = signal(2, Severity.IMPASSABLE, 20);

        assertThat(QuorumService.quorumLevel(List.of(ada, bola), bola, WINDOW, 2))
                .contains(Severity.ANKLE);
    }

    @Test
    @DisplayName("a report outside the window does not corroborate")
    void outsideTheWindowDoesNotCount() {
        ReportSignal ada = signal(1, Severity.IMPASSABLE, 0);
        ReportSignal bola = signal(2, Severity.IMPASSABLE, 46);

        assertThat(QuorumService.quorumLevel(List.of(ada, bola), bola, WINDOW, 2)).isEmpty();
    }

    @Test
    @DisplayName("the window boundary itself corroborates")
    void exactlyAtTheWindowEdgeCounts() {
        ReportSignal ada = signal(1, Severity.KNEE, 0);
        ReportSignal bola = signal(2, Severity.KNEE, 45);

        assertThat(QuorumService.quorumLevel(List.of(ada, bola), bola, WINDOW, 2))
                .contains(Severity.KNEE);
    }

    @Test
    @DisplayName("a quorum is about the report just filed, so the trigger must be one of the voices")
    void triggerMustBeCounted() {
        ReportSignal ada = signal(1, Severity.KNEE, 0);
        ReportSignal bola = signal(2, Severity.KNEE, 10);
        ReportSignal chidi = signal(3, Severity.KNEE, 0);

        // Chidi triggers, and is present, so this is a quorum.
        assertThat(QuorumService.quorumLevel(List.of(ada, bola, chidi), chidi, WINDOW, 2)).isPresent();

        // A trigger nobody recorded is not a quorum, however many others reported.
        ReportSignal stranger = signal(99, Severity.KNEE, 5);
        assertThat(QuorumService.quorumLevel(List.of(ada, bola), stranger, WINDOW, 2)).isEmpty();
    }

    @Test
    @DisplayName("a reporter's most severe reading is the one that represents them")
    void strongestReadingPerReporter() {
        ReportSignal adaAnkle = signal(1, Severity.ANKLE, 0);
        ReportSignal adaImpassable = signal(1, Severity.IMPASSABLE, 5);
        ReportSignal bola = signal(2, Severity.KNEE, 10);

        assertThat(QuorumService.quorumLevel(List.of(adaAnkle, adaImpassable, bola), bola, WINDOW, 2))
                .contains(Severity.KNEE);
    }

    @Test
    @DisplayName("with three reporters the level is the most severe that two of them saw")
    void generalisesBeyondTwo() {
        ReportSignal ada = signal(1, Severity.IMPASSABLE, 0);
        ReportSignal bola = signal(2, Severity.IMPASSABLE, 5);
        ReportSignal chidi = signal(3, Severity.ANKLE, 10);

        Optional<Severity> level = QuorumService.quorumLevel(List.of(ada, bola, chidi), chidi, WINDOW, 2);

        assertThat(level).contains(Severity.IMPASSABLE);
    }

    @Test
    @DisplayName("an empty history is not a quorum")
    void emptyHistory() {
        assertThat(QuorumService.quorumLevel(List.of(), signal(1, Severity.KNEE, 0), WINDOW, 2)).isEmpty();
    }
}
