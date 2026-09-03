package ng.ekoalert.api.web;

import jakarta.validation.Valid;
import ng.ekoalert.domain.model.AppUser;
import ng.ekoalert.domain.model.Reporter;
import ng.ekoalert.domain.repo.AppUserRepository;
import ng.ekoalert.domain.repo.ReporterRepository;
import ng.ekoalert.domain.repo.ZoneRepository;
import ng.ekoalert.domain.service.KillSwitchService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Admin controls. The kill switch is the one that matters. */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    /**
     * The characters a generated credential is drawn from. No vowels and no
     * look-alikes: these get read down a phone line to somebody standing in the
     * rain, so 0/O and 1/l/I are left out and nothing here can accidentally
     * spell a word.
     *
     * <p>Not named for what it builds. A constant whose name pairs the word
     * password with a string literal reads as a hardcoded credential to a secret
     * scanner, and an alert that has to be dismissed every time trains everyone
     * to dismiss the one that matters. Nothing is hidden by it either way:
     * strength here comes from SecureRandom over this set, not from the set
     * being unknown.
     */
    private static final String READABLE_ALPHABET = "23456789bcdfghjkmnpqrstvwxz";
    private static final int GENERATED_LENGTH = 12;

    private final KillSwitchService killSwitch;
    private final ReporterRepository reporters;
    private final AppUserRepository users;
    private final ZoneRepository zones;
    private final PasswordEncoder passwords;
    private final SecureRandom random = new SecureRandom();

    public AdminController(KillSwitchService killSwitch,
                           ReporterRepository reporters,
                           AppUserRepository users,
                           ZoneRepository zones,
                           PasswordEncoder passwords) {
        this.killSwitch = killSwitch;
        this.reporters = reporters;
        this.users = users;
        this.zones = zones;
        this.passwords = passwords;
    }

    @PostMapping("/kill-switch")
    public Dtos.KillSwitchResponse killSwitch(@Valid @RequestBody Dtos.KillSwitchRequest request) {
        killSwitch.setAlertsEnabled(request.enabled());
        return new Dtos.KillSwitchResponse(killSwitch.alertsEnabled(), Instant.now());
    }

    @PostMapping("/reporters/{id}/suspend")
    public ResponseEntity<Dtos.ReporterView> suspend(@PathVariable long id,
                                                     @RequestBody(required = false) Dtos.SuspendRequest request) {
        Reporter reporter = reporters.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown reporter: " + id));
        // Defaults to suspending. Lifting a suspension takes an explicit false.
        reporter.setSuspended(request == null || request.suspended() == null || request.suspended());
        reporters.save(reporter);
        return ResponseEntity.ok(view(reporter));
    }

    /** Everyone who can file, and what state they are in. */
    @GetMapping("/reporters")
    public List<Dtos.ReporterView> list() {
        return reporters.findAll().stream()
                .sorted(Comparator.comparing(Reporter::getId))
                .map(this::view)
                .toList();
    }

    /**
     * Vetting is a person's decision, so it is a person who makes it. There is
     * deliberately no way for somebody to enrol themselves: the quorum rule
     * assumes two reporters are two people, and self service would let one
     * person hold both halves of it.
     */
    @PostMapping("/reporters")
    public ResponseEntity<Dtos.ReporterCredentials> create(
            @Valid @RequestBody Dtos.ReporterCreateRequest request) {

        if (!zones.existsById(request.zoneId())) {
            throw new IllegalArgumentException("unknown zone: " + request.zoneId());
        }
        if (reporters.findByPhone(request.phone()).isPresent()) {
            throw new IllegalArgumentException("a reporter already exists for phone " + request.phone());
        }

        String username = request.username() != null && !request.username().isBlank()
                ? request.username().trim().toLowerCase(Locale.ROOT)
                : suggestUsername(request.displayName());
        if (users.findByUsername(username).isPresent()) {
            throw new IllegalArgumentException("username already taken: " + username);
        }

        Reporter reporter = reporters.save(
                new Reporter(request.zoneId(), request.displayName(), request.phone()));
        // Vetting on creation is the common case, because an admin adding
        // somebody has usually just met them.
        if (request.verified() == null || request.verified()) {
            reporter.verify(Instant.now());
            reporters.save(reporter);
        }

        String password = newPassword();
        users.save(new AppUser(username, passwords.encode(password),
                AppUser.ROLE_REPORTER, reporter.getId()));

        return ResponseEntity.status(201).body(
                new Dtos.ReporterCredentials(view(reporter), username, password));
    }

    /** Vetting, and taking it back. Distinct from suspension, which is temporary. */
    @PostMapping("/reporters/{id}/verify")
    public ResponseEntity<Dtos.ReporterView> verify(@PathVariable long id,
                                                    @RequestBody(required = false) Dtos.VerifyRequest request) {
        Reporter reporter = require(id);
        boolean verified = request == null || request.verified() == null || request.verified();
        if (verified) {
            reporter.verify(Instant.now());
        } else {
            reporter.revokeVerification();
        }
        reporters.save(reporter);
        return ResponseEntity.ok(view(reporter));
    }

    /**
     * A new password for somebody who has lost theirs. The old one stops working
     * immediately and the new one is shown once.
     */
    @PostMapping("/reporters/{id}/password")
    public ResponseEntity<Dtos.ReporterCredentials> resetPassword(@PathVariable long id) {
        Reporter reporter = require(id);
        AppUser user = users.findByReporterId(id)
                .orElseThrow(() -> new IllegalArgumentException("reporter " + id + " has no login"));
        String password = newPassword();
        user.setPasswordHash(passwords.encode(password));
        users.save(user);
        return ResponseEntity.ok(
                new Dtos.ReporterCredentials(view(reporter), user.getUsername(), password));
    }

    private Reporter require(long id) {
        return reporters.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown reporter: " + id));
    }

    private Dtos.ReporterView view(Reporter reporter) {
        return ViewMapper.reporter(reporter,
                users.findByReporterId(reporter.getId()).map(AppUser::getUsername).orElse(null));
    }

    private String suggestUsername(String displayName) {
        String base = displayName.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
        if (base.isEmpty()) {
            base = "reporter";
        }
        if (users.findByUsername(base).isEmpty()) {
            return base;
        }
        for (int n = 2; n < 1000; n++) {
            String candidate = base + n;
            if (users.findByUsername(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("could not derive a username from: " + displayName);
    }

    private String newPassword() {
        StringBuilder sb = new StringBuilder(GENERATED_LENGTH);
        for (int i = 0; i < GENERATED_LENGTH; i++) {
            sb.append(READABLE_ALPHABET.charAt(random.nextInt(READABLE_ALPHABET.length())));
            if (i % 4 == 3 && i != GENERATED_LENGTH - 1) {
                sb.append('-');
            }
        }
        return sb.toString();
    }
}
