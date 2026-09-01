package ng.ekoalert.app;

import ng.ekoalert.domain.model.AppUser;
import ng.ekoalert.domain.model.DrainageEdge;
import ng.ekoalert.domain.model.Reporter;
import ng.ekoalert.domain.model.Zone;
import ng.ekoalert.domain.repo.AlertDeliveryRepository;
import ng.ekoalert.domain.repo.AlertRepository;
import ng.ekoalert.domain.repo.AppUserRepository;
import ng.ekoalert.domain.repo.DrainageEdgeRepository;
import ng.ekoalert.domain.repo.EdgeCorrectionRepository;
import ng.ekoalert.domain.repo.ReportRepository;
import ng.ekoalert.domain.repo.ReporterRepository;
import ng.ekoalert.domain.repo.SubscriptionRepository;
import ng.ekoalert.domain.repo.SystemFlagRepository;
import ng.ekoalert.domain.repo.ZoneRepository;
import ng.ekoalert.domain.repo.ZoneStatusRepository;
import ng.ekoalert.engine.Confidence;
import org.junit.jupiter.api.BeforeEach;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;

/**
 * Layer 3 base. A real PostGIS database and the real Flyway migrations.
 *
 * <p>One container is shared across the whole run; starting a database per class
 * would multiply the suite time and prove nothing extra. Tables are truncated
 * between tests rather than rolled back, so each test sees exactly what the
 * services committed, which is the thing being verified.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(RecordingAlertPublisher.Config.class)
public abstract class IntegrationTestBase {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ekoalert")
            .withUsername("ekoalert")
            .withPassword("ekoalert")
            // Lets the driver hand plain text to the edge_confidence and
            // severity enum columns, exactly as the deployed datasource does.
            .withUrlParam("stringtype", "unspecified");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final GeometryFactory GEOMETRY = new GeometryFactory(new PrecisionModel(), 4326);

    protected static final Instant NOON = Instant.parse("2026-06-15T12:00:00Z");

    @Autowired protected ZoneRepository zones;
    @Autowired protected DrainageEdgeRepository edges;
    @Autowired protected ReporterRepository reporters;
    @Autowired protected ReportRepository reports;
    @Autowired protected ZoneStatusRepository zoneStatuses;
    @Autowired protected AlertRepository alerts;
    @Autowired protected AlertDeliveryRepository deliveries;
    @Autowired protected EdgeCorrectionRepository corrections;
    @Autowired protected SubscriptionRepository subscriptions;
    @Autowired protected SystemFlagRepository flags;
    @Autowired protected AppUserRepository users;
    @Autowired protected PasswordEncoder passwords;
    @Autowired protected RecordingAlertPublisher published;
    @Autowired protected JdbcTemplate jdbc;

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("""
                TRUNCATE alert_delivery, alert, edge_correction, report, zone_status,
                         subscription, app_user, reporter, edge, zone
                RESTART IDENTITY CASCADE
                """);
        jdbc.update("UPDATE system_flag SET value = 'true' WHERE key = 'alerts_enabled'");
        published.reset();
    }

    // ---------- fixtures ----------

    protected Zone zone(String id) {
        // Coordinates are arbitrary but distinct; nothing under test reads them
        // except the proposed edge estimate, which has its own test.
        int index = Integer.parseInt(id.substring(1));
        Point point = GEOMETRY.createPoint(new Coordinate(3.38 + index * 0.01, 6.53 + index * 0.01));
        point.setSRID(4326);
        return zones.save(new Zone(id, "Test Corridor", point, false));
    }

    protected DrainageEdge edge(String from, String to, int minutes, Confidence confidence) {
        DrainageEdge edge = new DrainageEdge(from, to, minutes, minutes * 55, "test fixture");
        edge.setConfidence(confidence);
        return edges.save(edge);
    }

    /** A vetted, unsuspended reporter: the only kind whose reports count. */
    protected Reporter verifiedReporter(String name, String zoneId) {
        Reporter reporter = reporters.save(new Reporter(zoneId, name, "+234800" + name.hashCode()));
        reporter.verify(NOON.minusSeconds(86_400));
        return reporters.save(reporter);
    }

    protected Reporter unverifiedReporter(String name, String zoneId) {
        return reporters.save(new Reporter(zoneId, name, "+234801" + name.hashCode()));
    }

    protected AppUser adminUser(String username, String password) {
        return users.save(new AppUser(username, passwords.encode(password), AppUser.ROLE_ADMIN, null));
    }

    protected AppUser reporterUser(String username, String password, Reporter reporter) {
        return users.save(new AppUser(username, passwords.encode(password),
                AppUser.ROLE_REPORTER, reporter.getId()));
    }
}
