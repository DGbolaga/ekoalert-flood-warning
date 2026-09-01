package ng.ekoalert.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ng.ekoalert.domain.model.DrainageEdge;
import ng.ekoalert.domain.model.Reporter;
import ng.ekoalert.engine.Confidence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Layer 4. Status codes, auth, payload shapes. */
@AutoConfigureMockMvc
class ApiIT extends IntegrationTestBase {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private Reporter ada;
    private Reporter bola;
    private DrainageEdge firstHop;

    @BeforeEach
    void fixtures() {
        zone("Z01");
        zone("Z02");
        firstHop = edge("Z01", "Z02", 16, Confidence.CONFIRMED);

        ada = verifiedReporter("ada", "Z01");
        bola = verifiedReporter("bola", "Z01");
        reporterUser("ada", PASSWORD, ada);
        reporterUser("bola", PASSWORD, bola);
        adminUser("admin", PASSWORD);
    }

    private String token(String username) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"%s","password":"%s"}""".formatted(username, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    // ---------- public reads ----------

    @Test
    @DisplayName("the map is public and reports zones with their status")
    void zonesArePublic() throws Exception {
        mvc.perform(get("/api/v1/zones"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value("Z01"))
                // Null until a field survey fills it, and the client falls back
                // to the id rather than inventing a name.
                .andExpect(jsonPath("$[0].name").doesNotExist())
                .andExpect(jsonPath("$[0].displayName").value("Z01"))
                .andExpect(jsonPath("$[0].status.active").value(false));
    }

    @Test
    @DisplayName("the graph is public and counts what it holds")
    void graphIsPublic() throws Exception {
        mvc.perform(get("/api/v1/graph"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.zones").value(2))
                .andExpect(jsonPath("$.counts.edges").value(1))
                .andExpect(jsonPath("$.counts.confirmed").value(1))
                .andExpect(jsonPath("$.edges[0].alertable").value(true));
    }

    @Test
    @DisplayName("zone detail lists inbound and outbound edges")
    void zoneDetail() throws Exception {
        mvc.perform(get("/api/v1/zones/Z01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.zone.id").value("Z01"))
                .andExpect(jsonPath("$.outbound.length()").value(1))
                .andExpect(jsonPath("$.outbound[0].toZone").value("Z02"))
                .andExpect(jsonPath("$.inbound.length()").value(0));
    }

    @Test
    @DisplayName("an unknown zone is a 404")
    void unknownZone() throws Exception {
        mvc.perform(get("/api/v1/zones/Z99")).andExpect(status().isNotFound());
    }

    // ---------- auth ----------

    @Test
    @DisplayName("filing a report without a token is a 401")
    void reportsNeedAuth() throws Exception {
        mvc.perform(post("/api/v1/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"level":"KNEE"}"""))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a wrong password is a 401 and says nothing about which half was wrong")
    void wrongPassword() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"ada","password":"not-it"}"""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("username or password is wrong"));
    }

    @Test
    @DisplayName("an unknown username gets the same 401 as a wrong password")
    void unknownUsername() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"nobody","password":"not-it"}"""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("username or password is wrong"));
    }

    @Test
    @DisplayName("a reporter cannot touch the kill switch")
    void reporterCannotUseTheKillSwitch() throws Exception {
        mvc.perform(post("/api/v1/admin/kill-switch")
                        .header("Authorization", "Bearer " + token("ada"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":false}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an admin can halt alerting")
    void adminCanUseTheKillSwitch() throws Exception {
        mvc.perform(post("/api/v1/admin/kill-switch")
                        .header("Authorization", "Bearer " + token("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":false}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alertsEnabled").value(false));

        assertThat(flags.findById("alerts_enabled")).get()
                .satisfies(flag -> assertThat(flag.getValue()).isEqualTo("false"));
    }

    // ---------- reports ----------

    @Test
    @DisplayName("one report is a 201 that says plainly it did not escalate anything")
    void oneReport() throws Exception {
        mvc.perform(post("/api/v1/reports")
                        .header("Authorization", "Bearer " + token("ada"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"level":"IMPASSABLE","observedAt":"2026-06-15T12:00:00Z"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.zoneId").value("Z01"))
                .andExpect(jsonPath("$.escalated").value(false))
                .andExpect(jsonPath("$.alerts.length()").value(0));
    }

    @Test
    @DisplayName("the second report escalates and the response carries the alerts")
    void twoReportsEscalate() throws Exception {
        mvc.perform(post("/api/v1/reports")
                .header("Authorization", "Bearer " + token("ada"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"level":"IMPASSABLE","observedAt":"2026-06-15T12:00:00Z"}"""));

        mvc.perform(post("/api/v1/reports")
                        .header("Authorization", "Bearer " + token("bola"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"level":"IMPASSABLE","observedAt":"2026-06-15T12:10:00Z"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.escalated").value(true))
                .andExpect(jsonPath("$.quorumLevel").value("IMPASSABLE"))
                .andExpect(jsonPath("$.alerts.length()").value(1))
                .andExpect(jsonPath("$.alerts[0].targetZone").value("Z02"))
                .andExpect(jsonPath("$.alerts[0].level").value("KNEE"))
                .andExpect(jsonPath("$.alerts[0].etaMinutes").value(16));
    }

    @Test
    @DisplayName("a missing level is a 400 naming the field")
    void missingLevelIsRejected() throws Exception {
        mvc.perform(post("/api/v1/reports")
                        .header("Authorization", "Bearer " + token("ada"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("level")));
    }

    @Test
    @DisplayName("reporting into a zone the reporter is not vetted for is a 400")
    void wrongZoneIsRejected() throws Exception {
        mvc.perform(post("/api/v1/reports")
                        .header("Authorization", "Bearer " + token("ada"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"Z02","level":"KNEE"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
    }

    // ---------- corrections ----------

    @Test
    @DisplayName("confirming is one POST and the response says how close the edge is to flipping")
    void confirmIsOneTap() throws Exception {
        mvc.perform(post("/api/v1/edges/" + firstHop.getId() + "/confirm")
                        .header("Authorization", "Bearer " + token("ada")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("confirm"))
                .andExpect(jsonPath("$.distinctVoices").value(1))
                .andExpect(jsonPath("$.threshold").value(2))
                .andExpect(jsonPath("$.thresholdMet").value(false));
    }

    @Test
    @DisplayName("confirming an edge that does not exist is a 400")
    void confirmUnknownEdge() throws Exception {
        mvc.perform(post("/api/v1/edges/9999/confirm")
                        .header("Authorization", "Bearer " + token("ada")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("proposing an edge takes the two zones and nothing else")
    void proposeAnEdge() throws Exception {
        mvc.perform(post("/api/v1/edges/propose")
                        .header("Authorization", "Bearer " + token("ada"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromZone":"Z02","toZone":"Z01"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("propose"))
                .andExpect(jsonPath("$.thresholdMet").value(false));
    }

    // ---------- subscriptions and stream ----------

    @Test
    @DisplayName("subscribing needs no login")
    void subscribeIsPublic() throws Exception {
        mvc.perform(post("/api/v1/subscriptions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"zoneId":"Z02","address":"resident-1"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.zoneId").value("Z02"))
                .andExpect(jsonPath("$.channel").value("sse"));
    }

    @Test
    @DisplayName("subscribing twice returns the same subscription")
    void subscribingIsIdempotent() throws Exception {
        String body = """
                {"zoneId":"Z02","address":"resident-1"}""";
        mvc.perform(post("/api/v1/subscriptions").contentType(MediaType.APPLICATION_JSON).content(body));
        mvc.perform(post("/api/v1/subscriptions").contentType(MediaType.APPLICATION_JSON).content(body));

        assertThat(subscriptions.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("the alert stream is public and opens as an event stream")
    void streamIsPublic() throws Exception {
        mvc.perform(get("/api/v1/alerts/stream"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentType())
                        .startsWith(MediaType.TEXT_EVENT_STREAM_VALUE));
    }

    // ---------- replay ----------

    @Test
    @DisplayName("replay is admin only")
    void replayIsAdminOnly() throws Exception {
        mvc.perform(post("/api/v1/replay")
                        .header("Authorization", "Bearer " + token("ada"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reports":[]}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("replay returns what would have fired and writes nothing")
    void replayReturnsPredictions() throws Exception {
        String body = """
                {
                  "reports": [
                    {"zoneId":"Z01","reporterId":1,"level":"IMPASSABLE","observedAt":"2026-06-15T12:00:00Z"},
                    {"zoneId":"Z01","reporterId":2,"level":"IMPASSABLE","observedAt":"2026-06-15T12:10:00Z"}
                  ]
                }""";

        MvcResult result = mvc.perform(post("/api/v1/replay")
                        .header("Authorization", "Bearer " + token("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.reportsReplayed").value(2))
                .andExpect(jsonPath("$.summary.zonesEscalated").value(1))
                .andExpect(jsonPath("$.alerts[0].targetZone").value("Z02"))
                .andExpect(jsonPath("$.alerts[0].expectedArrival").value("2026-06-15T12:26:00Z"))
                .andExpect(jsonPath("$.alerts[0].wouldDeliver").value(true))
                .andReturn();

        // Nothing was written and nothing was sent.
        assertThat(alerts.count()).isZero();
        assertThat(reports.count()).isZero();
        assertThat(published.fired).isEmpty();

        JsonNode first = json.readTree(result.getResponse().getContentAsString());
        MvcResult again = mvc.perform(post("/api/v1/replay")
                        .header("Authorization", "Bearer " + token("admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        assertThat(json.readTree(again.getResponse().getContentAsString())).isEqualTo(first);
    }
}
