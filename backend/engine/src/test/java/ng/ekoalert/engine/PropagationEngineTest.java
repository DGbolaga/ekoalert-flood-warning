package ng.ekoalert.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Layer 1. Hand-built graphs, no Spring, no database, no mocks.
 *
 * These were written before the engine they cover. If one fails, the engine is
 * wrong. Change the code, not the assertion.
 */
class PropagationEngineTest {

    private final PropagationEngine engine = new BestFirstPropagationEngine();

    private static final PropagationConfig STRICT = new PropagationConfig(3, true);
    private static final PropagationConfig LENIENT = new PropagationConfig(3, false);

    // ---------- helpers ----------

    private static ZoneId z(String id) {
        return new ZoneId(id);
    }

    private static Edge confirmed(String from, String to, int minutes) {
        return new Edge(z(from), z(to), minutes, Confidence.CONFIRMED, false);
    }

    private static Edge inferred(String from, String to, int minutes) {
        return new Edge(z(from), z(to), minutes, Confidence.INFERRED, false);
    }

    private static Edge rejected(String from, String to, int minutes) {
        return new Edge(z(from), z(to), minutes, Confidence.REJECTED, false);
    }

    private static Edge blocked(String from, String to, int minutes) {
        return new Edge(z(from), z(to), minutes, Confidence.CONFIRMED, true);
    }

    private static DrainageGraph graph(Edge... edges) {
        return new DrainageGraph(List.of(edges));
    }

    private static PropagatedAlert alert(String target, Severity level, int eta, int hops, boolean confirmed) {
        return new PropagatedAlert(z(target), level, eta, hops, confirmed);
    }

    // ---------- linear chain ----------

    @Test
    @DisplayName("linear chain settles in ETA order with severity decaying one step per hop")
    void linearChain() {
        DrainageGraph g = graph(
                confirmed("A", "B", 20),
                confirmed("B", "C", 15),
                confirmed("C", "D", 10));

        List<PropagatedAlert> out = engine.propagate(g, z("A"), Severity.IMPASSABLE, STRICT);

        assertThat(out).containsExactly(
                alert("B", Severity.KNEE, 20, 1, true),
                alert("C", Severity.ANKLE, 35, 2, true));
    }

    @Test
    @DisplayName("the origin is never emitted as its own alert")
    void originIsNeverEmitted() {
        DrainageGraph g = graph(confirmed("A", "B", 20));

        List<PropagatedAlert> out = engine.propagate(g, z("A"), Severity.IMPASSABLE, STRICT);

        assertThat(out).extracting(PropagatedAlert::target).doesNotContain(z("A"));
    }

    // ---------- cycles ----------

    @Test
    @DisplayName("a cycle terminates and each zone is emitted once at its lowest ETA")
    void cycleTerminatesAndEmitsOnce() {
        DrainageGraph g = graph(
                confirmed("A", "B", 20),
                confirmed("B", "C", 15),
                confirmed("C", "B", 5));

        List<PropagatedAlert> out = assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(2),
                () -> engine.propagate(g, z("A"), Severity.IMPASSABLE, STRICT));

        assertThat(out).containsExactly(
                alert("B", Severity.KNEE, 20, 1, true),
                alert("C", Severity.ANKLE, 35, 2, true));
    }

    @Test
    @DisplayName("a cycle back to the origin does not re-emit the origin")
    void cycleBackToOrigin() {
        DrainageGraph g = graph(
                confirmed("A", "B", 20),
                confirmed("B", "A", 5));

        List<PropagatedAlert> out = engine.propagate(g, z("A"), Severity.IMPASSABLE, STRICT);

        assertThat(out).containsExactly(alert("B", Severity.KNEE, 20, 1, true));
    }

    @Test
    @DisplayName("a self edge does not loop forever even though the schema forbids one")
    void selfEdgeDoesNotLoopForever() {
        DrainageGraph g = graph(
                new Edge(z("A"), z("A"), 5, Confidence.CONFIRMED, false),
                confirmed("A", "B", 20));

        List<PropagatedAlert> out = assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(2),
                () -> engine.propagate(g, z("A"), Severity.IMPASSABLE, STRICT));

        assertThat(out).containsExactly(alert("B", Severity.KNEE, 20, 1, true));
    }

    // ---------- blocked and rejected ----------

    @Test
    @DisplayName("a blocked edge is not traversed and everything behind it is unreachable")
    void blockedEdgeIsNotTraversed() {
        DrainageGraph g = graph(
                confirmed("A", "B", 20),
                blocked("B", "C", 15),
                confirmed("C", "D", 10));

        List<PropagatedAlert> out = engine.propagate(g, z("A"), Severity.IMPASSABLE, STRICT);

        assertThat(out).containsExactly(alert("B", Severity.KNEE, 20, 1, true));
    }

    @Test
    @DisplayName("a rejected edge is never traversed, whatever the config says")
    void rejectedEdgeIsNeverTraversed() {
        DrainageGraph g = graph(
                confirmed("A", "B", 20),
                rejected("B", "C", 15));

        assertThat(engine.propagate(g, z("A"), Severity.IMPASSABLE, STRICT))
                .containsExactly(alert("B", Severity.KNEE, 20, 1, true));
        assertThat(engine.propagate(g, z("A"), Severity.IMPASSABLE, LENIENT))
                .containsExactly(alert("B", Severity.KNEE, 20, 1, true));
    }

    @Test
    @DisplayName("a rejected edge does not hide a longer confirmed detour to the same zone")
    void rejectedEdgeDoesNotHideADetour() {
        DrainageGraph g = graph(
                rejected("A", "C", 5),
                confirmed("A", "B", 20),
                confirmed("B", "C", 15));

        List<PropagatedAlert> out = engine.propagate(g, z("A"), Severity.IMPASSABLE, STRICT);

        assertThat(out).containsExactly(
                alert("B", Severity.KNEE, 20, 1, true),
                alert("C", Severity.ANKLE, 35, 2, true));
    }

    // ---------- inferred edges, both ways ----------

    @Test
    @DisplayName("with requireConfirmedEdges true, an inferred edge is still traversed but the path is not confirmed")
    void inferredEdgeIsTraversedButNotConfirmed() {
        DrainageGraph g = graph(
                inferred("A", "B", 20),
                confirmed("B", "C", 15));

        List<PropagatedAlert> out = engine.propagate(g, z("A"), Severity.IMPASSABLE, STRICT);

        assertThat(out).containsExactly(
                alert("B", Severity.KNEE, 20, 1, false),
                alert("C", Severity.ANKLE, 35, 2, false));
    }

    @Test
    @DisplayName("one inferred edge anywhere on the path taints the whole path")
    void oneInferredEdgeTaintsTheWholePath() {
        DrainageGraph g = graph(
                confirmed("A", "B", 20),
                inferred("B", "C", 15));

        List<PropagatedAlert> out = engine.propagate(g, z("A"), Severity.IMPASSABLE, STRICT);

        assertThat(out).containsExactly(
                alert("B", Severity.KNEE, 20, 1, true),
                alert("C", Severity.ANKLE, 35, 2, false));
    }

    @Test
    @DisplayName("with requireConfirmedEdges false, confirmation is not being required so every path counts as deliverable")
    void inferredEdgeWithRequireConfirmedEdgesFalse() {
        DrainageGraph g = graph(
                inferred("A", "B", 20),
                confirmed("B", "C", 15));

        List<PropagatedAlert> out = engine.propagate(g, z("A"), Severity.IMPASSABLE, LENIENT);

        assertThat(out).containsExactly(
                alert("B", Severity.KNEE, 20, 1, true),
                alert("C", Severity.ANKLE, 35, 2, true));
    }

    // ---------- severity decay ----------

    @Test
    @DisplayName("an ANKLE origin decays below ANKLE at hop 1 and produces nothing")
    void ankleOriginProducesNothing() {
        DrainageGraph g = graph(
                confirmed("A", "B", 20),
                confirmed("B", "C", 15));

        assertThat(engine.propagate(g, z("A"), Severity.ANKLE, STRICT)).isEmpty();
    }

    @Test
    @DisplayName("a KNEE origin reaches hop 1 at ANKLE and stops")
    void kneeOriginReachesOneHop() {
        DrainageGraph g = graph(
                confirmed("A", "B", 20),
                confirmed("B", "C", 15));

        assertThat(engine.propagate(g, z("A"), Severity.KNEE, STRICT))
                .containsExactly(alert("B", Severity.ANKLE, 20, 1, true));
    }

    @Test
    @DisplayName("severity never escalates along a path")
    void severityNeverEscalates() {
        DrainageGraph g = graph(
                confirmed("A", "B", 20),
                confirmed("B", "C", 15));

        List<PropagatedAlert> out = engine.propagate(g, z("A"), Severity.IMPASSABLE, STRICT);

        assertThat(out).allSatisfy(a -> assertThat(a.level().ordinal())
                .isLessThan(Severity.IMPASSABLE.ordinal()));
    }

    // ---------- maxHops ----------

    @Test
    @DisplayName("maxHops 1 caps the walk at the first hop even when severity would allow more")
    void maxHopsOne() {
        DrainageGraph g = graph(
                confirmed("A", "B", 20),
                confirmed("B", "C", 15));

        assertThat(engine.propagate(g, z("A"), Severity.IMPASSABLE, new PropagationConfig(1, true)))
                .containsExactly(alert("B", Severity.KNEE, 20, 1, true));
    }

    @Test
    @DisplayName("maxHops 2 admits the second hop, which is the boundary")
    void maxHopsTwo() {
        DrainageGraph g = graph(
                confirmed("A", "B", 20),
                confirmed("B", "C", 15),
                confirmed("C", "D", 10));

        assertThat(engine.propagate(g, z("A"), Severity.IMPASSABLE, new PropagationConfig(2, true)))
                .containsExactly(
                        alert("B", Severity.KNEE, 20, 1, true),
                        alert("C", Severity.ANKLE, 35, 2, true));
    }

    @Test
    @DisplayName("maxHops 0 produces nothing")
    void maxHopsZero() {
        DrainageGraph g = graph(confirmed("A", "B", 20));

        assertThat(engine.propagate(g, z("A"), Severity.IMPASSABLE, new PropagationConfig(0, true)))
                .isEmpty();
    }

    // ---------- diamond ----------

    @Test
    @DisplayName("diamond graph: two paths reach the same zone, the lower ETA wins")
    void diamondLowerEtaWins() {
        DrainageGraph g = graph(
                confirmed("A", "B", 10),
                confirmed("A", "C", 40),
                confirmed("B", "D", 60),
                confirmed("C", "D", 5));

        List<PropagatedAlert> out = engine.propagate(g, z("A"), Severity.IMPASSABLE, STRICT);

        assertThat(out).containsExactly(
                alert("B", Severity.KNEE, 10, 1, true),
                alert("C", Severity.KNEE, 40, 1, true),
                alert("D", Severity.ANKLE, 45, 2, true));
    }

    @Test
    @DisplayName("diamond graph: the winning path also decides pathConfirmed")
    void diamondWinningPathDecidesConfirmation() {
        DrainageGraph g = graph(
                confirmed("A", "B", 10),
                confirmed("A", "C", 40),
                confirmed("B", "D", 60),
                inferred("C", "D", 5));

        List<PropagatedAlert> out = engine.propagate(g, z("A"), Severity.IMPASSABLE, STRICT);

        assertThat(out).contains(alert("D", Severity.ANKLE, 45, 2, false));
    }

    // ---------- degenerate graphs ----------

    @Test
    @DisplayName("a zone with no outbound edges produces no alerts and does not throw")
    void zoneWithNoOutboundEdges() {
        DrainageGraph g = graph(
                confirmed("A", "B", 20),
                confirmed("B", "C", 15));

        assertThat(engine.propagate(g, z("C"), Severity.IMPASSABLE, STRICT)).isEmpty();
    }

    @Test
    @DisplayName("an origin absent from the graph produces no alerts and does not throw")
    void unknownOrigin() {
        DrainageGraph g = graph(confirmed("A", "B", 20));

        assertThat(engine.propagate(g, z("ZZZ"), Severity.IMPASSABLE, STRICT)).isEmpty();
    }

    @Test
    @DisplayName("an empty graph produces no alerts")
    void emptyGraph() {
        assertThat(engine.propagate(new DrainageGraph(List.of()), z("A"), Severity.IMPASSABLE, STRICT))
                .isEmpty();
    }

    @Test
    @DisplayName("the graph builds its adjacency once and stays immutable")
    void graphIsImmutable() {
        java.util.List<Edge> mutable = new java.util.ArrayList<>();
        mutable.add(confirmed("A", "B", 20));
        DrainageGraph g = new DrainageGraph(mutable);

        mutable.add(confirmed("B", "C", 15));

        assertThat(engine.propagate(g, z("A"), Severity.IMPASSABLE, STRICT))
                .containsExactly(alert("B", Severity.KNEE, 20, 1, true));
    }
}
