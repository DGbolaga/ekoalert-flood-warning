package ng.ekoalert.app;

import ng.ekoalert.domain.model.AppUser;
import ng.ekoalert.domain.model.Reporter;
import ng.ekoalert.domain.model.Zone;
import ng.ekoalert.domain.repo.AppUserRepository;
import ng.ekoalert.domain.repo.ReporterRepository;
import ng.ekoalert.domain.repo.ZoneRepository;
import ng.ekoalert.domain.seed.SeedLoader;
import ng.ekoalert.domain.seed.SeedResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Populates the database so the API can be exercised without a UI.
 *
 * <p>Run with {@code --seed} to load the zones and edges, and additionally
 * {@code --demo-users} to create an admin and two reporters in the same zone so
 * the quorum path can be driven end to end. Both are idempotent.
 */
@Component
public class SeedCommand implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedCommand.class);

    /** Only ever used behind --demo-users, and refused once a real deployment sets its own. */
    private static final String DEMO_PASSWORD = "ekoalert-demo";

    private final SeedLoader loader;
    private final ZoneRepository zones;
    private final ReporterRepository reporters;
    private final AppUserRepository users;
    private final PasswordEncoder passwords;

    public SeedCommand(SeedLoader loader,
                       ZoneRepository zones,
                       ReporterRepository reporters,
                       AppUserRepository users,
                       PasswordEncoder passwords) {
        this.loader = loader;
        this.zones = zones;
        this.reporters = reporters;
        this.users = users;
        this.passwords = passwords;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("seed") && !args.containsOption("demo-users")) {
            return;
        }

        if (args.containsOption("seed")) {
            SeedResult result = loader.load();
            log.info("SEED {}", result.summary());
            result.mergedDuplicates().forEach(line -> log.info("SEED merged: {}", line));
            result.skippedEdges().forEach(line -> log.info("SEED skipped: {}", line));
        }

        if (args.containsOption("demo-users")) {
            createDemoUsers();
        }
    }

    private void createDemoUsers() {
        List<Zone> all = zones.findAllByOrderByIdAsc();
        if (all.isEmpty()) {
            log.warn("no zones loaded, so there is nowhere to put a demo reporter. Run with --seed first.");
            return;
        }
        String zoneId = all.get(0).getId();

        admin("admin");
        reporter("ada", "+2348000000001", zoneId);
        reporter("bola", "+2348000000002", zoneId);

        log.info("SEED demo users ready: admin, ada, bola. Password for all three is {}. "
                + "Both reporters are vetted for zone {}, which is what makes a quorum possible.",
                DEMO_PASSWORD, zoneId);
    }

    private void admin(String username) {
        if (users.findByUsername(username).isPresent()) {
            return;
        }
        users.save(new AppUser(username, passwords.encode(DEMO_PASSWORD), AppUser.ROLE_ADMIN, null));
    }

    private void reporter(String username, String phone, String zoneId) {
        if (users.findByUsername(username).isPresent()) {
            return;
        }
        Reporter reporter = reporters.findByPhone(phone)
                .orElseGet(() -> reporters.save(new Reporter(zoneId, username, phone)));
        if (reporter.getVerifiedAt() == null) {
            reporter.verify(Instant.now());
            reporters.save(reporter);
        }
        users.save(new AppUser(username, passwords.encode(DEMO_PASSWORD),
                AppUser.ROLE_REPORTER, reporter.getId()));
    }
}
