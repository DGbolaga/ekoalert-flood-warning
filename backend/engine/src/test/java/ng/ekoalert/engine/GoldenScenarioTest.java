package ng.ekoalert.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Layer 2. Drives the fixed acceptance case at golden/scenario-01.json.
 *
 * The scenario file is immutable. Its expected outputs were worked out by hand
 * and are not negotiable. If a case fails, the engine is wrong.
 */
class GoldenScenarioTest {

    private static final String RESOURCE = "/golden/scenario-01.json";

    private final PropagationEngine engine = new BestFirstPropagationEngine();
    private final ObjectMapper mapper = new ObjectMapper();

    @TestFactory
    List<DynamicTest> goldenScenario() throws Exception {
        JsonNode root = read();
        List<Edge> baseEdges = parseEdges(root.path("graph").path("edges"));

        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : root.path("cases")) {
            String name = "case " + c.path("id").asInt() + ": " + c.path("description").asText();
            tests.add(DynamicTest.dynamicTest(name, () -> runCase(baseEdges, c)));
        }
        assertThat(tests).hasSize(5);
        return tests;
    }

    private void runCase(List<Edge> baseEdges, JsonNode c) {
        DrainageGraph graph = new DrainageGraph(applyOverrides(baseEdges, c.path("edgeOverrides")));
        ZoneId origin = new ZoneId(c.path("origin").asText());
        Severity originLevel = Severity.valueOf(c.path("originLevel").asText());
        PropagationConfig config = new PropagationConfig(
                c.path("config").path("maxHops").asInt(),
                c.path("config").path("requireConfirmedEdges").asBoolean());

        List<PropagatedAlert> actual = engine.propagate(graph, origin, originLevel, config);
        List<PropagatedAlert> expected = parseExpected(c.path("expected"));

        // Order matters: rule 1 says traversal is best-first by cumulative ETA.
        assertThat(actual).containsExactlyElementsOf(expected);
    }

    private List<Edge> parseEdges(JsonNode array) {
        List<Edge> edges = new ArrayList<>();
        for (JsonNode e : array) {
            edges.add(new Edge(
                    new ZoneId(e.path("from").asText()),
                    new ZoneId(e.path("to").asText()),
                    e.path("travelMinutes").asInt(),
                    Confidence.valueOf(e.path("confidence").asText()),
                    e.path("blocked").asBoolean()));
        }
        return edges;
    }

    /** Rewrites only the fields an override actually names, leaving the rest of the base edge alone. */
    private List<Edge> applyOverrides(List<Edge> baseEdges, JsonNode overrides) {
        List<Edge> edges = new ArrayList<>(baseEdges);
        for (JsonNode o : overrides) {
            ZoneId from = new ZoneId(o.path("from").asText());
            ZoneId to = new ZoneId(o.path("to").asText());
            boolean matched = false;
            for (int i = 0; i < edges.size(); i++) {
                Edge e = edges.get(i);
                if (!e.from().equals(from) || !e.to().equals(to)) {
                    continue;
                }
                edges.set(i, new Edge(
                        e.from(),
                        e.to(),
                        o.has("travelMinutes") ? o.get("travelMinutes").asInt() : e.travelMinutes(),
                        o.has("confidence") ? Confidence.valueOf(o.get("confidence").asText()) : e.confidence(),
                        o.has("blocked") ? o.get("blocked").asBoolean() : e.blocked()));
                matched = true;
            }
            assertThat(matched)
                    .as("override names edge %s -> %s which is not in the golden graph", from.value(), to.value())
                    .isTrue();
        }
        return edges;
    }

    private List<PropagatedAlert> parseExpected(JsonNode array) {
        List<PropagatedAlert> expected = new ArrayList<>();
        for (JsonNode a : array) {
            expected.add(new PropagatedAlert(
                    new ZoneId(a.path("target").asText()),
                    Severity.valueOf(a.path("level").asText()),
                    a.path("etaMinutes").asInt(),
                    a.path("hops").asInt(),
                    a.path("pathConfirmed").asBoolean()));
        }
        return expected;
    }

    private JsonNode read() throws Exception {
        try (InputStream in = GoldenScenarioTest.class.getResourceAsStream(RESOURCE)) {
            assertThat(in).as("golden scenario resource %s must exist", RESOURCE).isNotNull();
            return mapper.readTree(in);
        }
    }
}
