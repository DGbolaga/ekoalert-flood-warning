package ng.ekoalert.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ng.ekoalert.domain.model.Reporter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Enrolling and vetting reporters, over real HTTP.
 *
 * <p>The quorum rule rests entirely on a reporter being a vetted person, so the
 * cases that matter here are the ones where somebody should stop counting:
 * unvetted, revoked, suspended. A password that is issued and then does not work
 * is the same failure wearing a different hat, so it is checked by using it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReporterAdminHttpIT extends IntegrationTestBase {

    private static final String PASSWORD = "correct-horse-battery-staple";

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Autowired ObjectMapper json;

    @LocalServerPort int port;

    @BeforeEach
    void fixtures() {
        zone("Z01");
        zone("Z02");
        adminUser("admin", PASSWORD);
    }

    private HttpResponse<String> send(String method, String path, String body, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json");
        if ("GET".equals(method)) {
            request.GET();
        } else {
            request.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
        }
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String token(String username, String password) throws Exception {
        HttpResponse<String> response = send("POST", "/api/v1/auth/login", """
                {"username":"%s","password":"%s"}""".formatted(username, password), null);
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body()).get("token").asText();
    }

    private JsonNode createReporter(String admin, String name, String zone, String phone) throws Exception {
        HttpResponse<String> response = send("POST", "/api/v1/admin/reporters", """
                {"zoneId":"%s","displayName":"%s","phone":"%s"}""".formatted(zone, name, phone), admin);
        assertThat(response.statusCode()).isEqualTo(201);
        return json.readTree(response.body());
    }

    @Test
    @DisplayName("an admin enrols a reporter and the issued password signs them in")
    void enrolAndSignIn() throws Exception {
        String admin = token("admin", PASSWORD);
        JsonNode created = createReporter(admin, "Ada", "Z01", "+2348001111111");

        assertThat(created.get("username").asText()).isEqualTo("ada");
        String issued = created.get("password").asText();
        assertThat(issued).isNotBlank();

        // The credential is only real if it opens the door.
        assertThat(token("ada", issued)).isNotBlank();
    }

    @Test
    @DisplayName("an enrolled reporter is vetted by default and counts toward a quorum")
    void enrolledReporterCounts() throws Exception {
        String admin = token("admin", PASSWORD);
        JsonNode created = createReporter(admin, "Ada", "Z01", "+2348001111111");
        assertThat(created.get("reporter").has("verifiedAt")).isTrue();

        String reporter = token("ada", created.get("password").asText());
        assertThat(send("POST", "/api/v1/reports", """
                {"zoneId":"Z01","level":"KNEE"}""", reporter).statusCode()).isEqualTo(201);
    }

    @Test
    @DisplayName("vetting can be withheld at enrolment")
    void canEnrolWithoutVetting() throws Exception {
        String admin = token("admin", PASSWORD);
        HttpResponse<String> response = send("POST", "/api/v1/admin/reporters", """
                {"zoneId":"Z01","displayName":"Ada","phone":"+2348001111111","verified":false}""", admin);
        assertThat(response.statusCode()).isEqualTo(201);
        // Null fields are omitted API wide, so being unvetted reads as an absent
        // key rather than a null one.
        assertThat(json.readTree(response.body()).get("reporter").has("verifiedAt")).isFalse();
    }

    @Test
    @DisplayName("revoking vetting stops the reports counting, and restoring it resumes")
    void vettingCanBeRevokedAndRestored() throws Exception {
        String admin = token("admin", PASSWORD);
        JsonNode created = createReporter(admin, "Ada", "Z01", "+2348001111111");
        long id = created.get("reporter").get("id").asLong();

        HttpResponse<String> revoked = send("POST", "/api/v1/admin/reporters/" + id + "/verify", """
                {"verified":false}""", admin);
        assertThat(revoked.statusCode()).isEqualTo(200);
        assertThat(json.readTree(revoked.body()).has("verifiedAt")).isFalse();

        Reporter after = reporters.findById(id).orElseThrow();
        assertThat(after.countsTowardQuorum()).isFalse();

        HttpResponse<String> restored = send("POST", "/api/v1/admin/reporters/" + id + "/verify", null, admin);
        assertThat(restored.statusCode()).isEqualTo(200);
        assertThat(reporters.findById(id).orElseThrow().countsTowardQuorum()).isTrue();
    }

    @Test
    @DisplayName("a suspended reporter still files, and still does not count")
    void suspensionKeepsTheReportButNotTheVote() throws Exception {
        String admin = token("admin", PASSWORD);
        JsonNode created = createReporter(admin, "Ada", "Z01", "+2348001111111");
        long id = created.get("reporter").get("id").asLong();
        String reporter = token("ada", created.get("password").asText());

        assertThat(send("POST", "/api/v1/admin/reporters/" + id + "/suspend", """
                {"suspended":true}""", admin).statusCode()).isEqualTo(200);

        assertThat(send("POST", "/api/v1/reports", """
                {"zoneId":"Z01","level":"KNEE"}""", reporter).statusCode()).isEqualTo(201);
        assertThat(reporters.findById(id).orElseThrow().countsTowardQuorum()).isFalse();
    }

    @Test
    @DisplayName("a reset password replaces the old one rather than sitting beside it")
    void resetPasswordInvalidatesTheOld() throws Exception {
        String admin = token("admin", PASSWORD);
        JsonNode created = createReporter(admin, "Ada", "Z01", "+2348001111111");
        String original = created.get("password").asText();
        long id = created.get("reporter").get("id").asLong();

        HttpResponse<String> reset = send("POST", "/api/v1/admin/reporters/" + id + "/password", null, admin);
        assertThat(reset.statusCode()).isEqualTo(200);
        String replacement = json.readTree(reset.body()).get("password").asText();
        assertThat(replacement).isNotEqualTo(original);

        assertThat(token("ada", replacement)).isNotBlank();
        assertThat(send("POST", "/api/v1/auth/login", """
                {"username":"ada","password":"%s"}""".formatted(original), null).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("the list shows every reporter with the login they sign in as")
    void listShowsLogins() throws Exception {
        String admin = token("admin", PASSWORD);
        createReporter(admin, "Ada", "Z01", "+2348001111111");
        createReporter(admin, "Bola", "Z02", "+2348002222222");

        HttpResponse<String> response = send("GET", "/api/v1/admin/reporters", null, admin);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode list = json.readTree(response.body());
        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("username").asText()).isEqualTo("ada");
        assertThat(list.get(1).get("zoneId").asText()).isEqualTo("Z02");
    }

    @Test
    @DisplayName("one person is not enrolled twice under the same phone")
    void phoneIsNotReused() throws Exception {
        String admin = token("admin", PASSWORD);
        createReporter(admin, "Ada", "Z01", "+2348001111111");
        assertThat(send("POST", "/api/v1/admin/reporters", """
                {"zoneId":"Z01","displayName":"Someone Else","phone":"+2348001111111"}""", admin)
                .statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("a clashing username is refused rather than silently shadowing a login")
    void usernameIsNotReused() throws Exception {
        String admin = token("admin", PASSWORD);
        createReporter(admin, "Ada", "Z01", "+2348001111111");
        assertThat(send("POST", "/api/v1/admin/reporters", """
                {"zoneId":"Z01","displayName":"Ada","phone":"+2348003333333","username":"ada"}""", admin)
                .statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("a second Ada gets her own login rather than a collision")
    void derivedUsernamesDoNotCollide() throws Exception {
        String admin = token("admin", PASSWORD);
        createReporter(admin, "Ada", "Z01", "+2348001111111");
        JsonNode second = createReporter(admin, "Ada", "Z02", "+2348004444444");
        assertThat(second.get("username").asText()).isEqualTo("ada2");
    }

    @Test
    @DisplayName("a reporter cannot be enrolled into a zone that does not exist")
    void zoneMustExist() throws Exception {
        String admin = token("admin", PASSWORD);
        assertThat(send("POST", "/api/v1/admin/reporters", """
                {"zoneId":"ZZZ","displayName":"Ada","phone":"+2348001111111"}""", admin)
                .statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("a reporter cannot enrol anybody, including themselves")
    void enrolmentIsAdminOnly() throws Exception {
        String admin = token("admin", PASSWORD);
        JsonNode created = createReporter(admin, "Ada", "Z01", "+2348001111111");
        String reporter = token("ada", created.get("password").asText());

        assertThat(send("POST", "/api/v1/admin/reporters", """
                {"zoneId":"Z01","displayName":"Sock Puppet","phone":"+2348009999999"}""", reporter)
                .statusCode()).isEqualTo(403);
        assertThat(send("GET", "/api/v1/admin/reporters", null, reporter).statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("an issued password is never readable afterwards")
    void passwordIsNotStoredInTheClear() throws Exception {
        String admin = token("admin", PASSWORD);
        JsonNode created = createReporter(admin, "Ada", "Z01", "+2348001111111");
        String issued = created.get("password").asText();

        assertThat(users.findByUsername("ada").orElseThrow().getPasswordHash()).doesNotContain(issued);
        assertThat(send("GET", "/api/v1/admin/reporters", null, admin).body()).doesNotContain(issued);
    }
}
