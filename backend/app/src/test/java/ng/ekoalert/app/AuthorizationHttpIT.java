package ng.ekoalert.app;

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
 * Authorisation over a real HTTP stack rather than MockMvc.
 *
 * <p>A denied request is forwarded to /error, and that forward re-enters the
 * security chain with the context already cleared. MockMvc performs no error
 * dispatch, so it cannot see that, and a 403 rewritten into a 401 passes there
 * unnoticed. This suite exists to catch exactly that.
 *
 * <p>It uses the JDK HTTP client rather than TestRestTemplate, because
 * HttpURLConnection underneath the latter tries to re-authenticate a POST when
 * it sees a 401 and throws instead of returning the status.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthorizationHttpIT extends IntegrationTestBase {

    private static final String PASSWORD = "correct-horse-battery-staple";

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Autowired ObjectMapper json;

    @LocalServerPort int port;

    private Reporter ada;

    @BeforeEach
    void fixtures() {
        zone("Z01");
        ada = verifiedReporter("ada", "Z01");
        reporterUser("ada", PASSWORD, ada);
        adminUser("admin", PASSWORD);
    }

    private HttpResponse<String> post(String path, String body, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String token(String username) throws Exception {
        HttpResponse<String> response = post("/api/v1/auth/login", """
                {"username":"%s","password":"%s"}""".formatted(username, PASSWORD), null);
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body()).get("token").asText();
    }

    @Test
    @DisplayName("an anonymous caller on a protected route gets 401")
    void anonymousGets401() throws Exception {
        assertThat(post("/api/v1/reports", """
                {"level":"KNEE"}""", null).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("a reporter on an admin route gets 403, not 401")
    void wrongRoleGets403() throws Exception {
        assertThat(post("/api/v1/admin/kill-switch", """
                {"enabled":false}""", token("ada")).statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("a reporter on the replay route gets 403, not 401")
    void replayIsAdminOnlyOverHttp() throws Exception {
        assertThat(post("/api/v1/replay", """
                {"reports":[]}""", token("ada")).statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("an admin on an admin route gets through")
    void adminGetsThrough() throws Exception {
        assertThat(post("/api/v1/admin/kill-switch", """
                {"enabled":false}""", token("admin")).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("a garbled token is anonymous, not a server error")
    void garbledTokenIsAnonymous() throws Exception {
        assertThat(post("/api/v1/reports", """
                {"level":"KNEE"}""", "not.a.token").statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("401 and 403 use the same error body as every other error")
    void errorBodyIsOneShape() throws Exception {
        HttpResponse<String> anonymous = post("/api/v1/reports", """
                {"level":"KNEE"}""", null);
        assertThat(json.readTree(anonymous.body()).get("error").asText()).isEqualTo("unauthorized");
        assertThat(json.readTree(anonymous.body()).has("at")).isTrue();

        HttpResponse<String> wrongRole = post("/api/v1/admin/kill-switch", """
                {"enabled":false}""", token("ada"));
        assertThat(json.readTree(wrongRole.body()).get("error").asText()).isEqualTo("forbidden");
        assertThat(json.readTree(wrongRole.body()).has("message")).isTrue();
    }

    @Test
    @DisplayName("the frontend dev server may preflight a report")
    void corsPreflightFromViteDevServer() throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/reports"))
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin"))
                .contains("http://localhost:5173");
    }

    @Test
    @DisplayName("an origin nobody configured is refused")
    void corsRejectsUnknownOrigin() throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/reports"))
                .header("Origin", "http://evil.example")
                .header("Access-Control-Request-Method", "POST")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    @Test
    @DisplayName("a reporter can still file a report over real HTTP")
    void reporterCanStillReport() throws Exception {
        assertThat(post("/api/v1/reports", """
                {"level":"KNEE"}""", token("ada")).statusCode()).isEqualTo(201);
    }
}
