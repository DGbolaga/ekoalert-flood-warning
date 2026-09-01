package ng.ekoalert.api.web;

import jakarta.validation.Valid;
import ng.ekoalert.api.security.JwtService;
import ng.ekoalert.domain.model.AppUser;
import ng.ekoalert.domain.repo.AppUserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AppUserRepository users;
    private final PasswordEncoder passwords;
    private final JwtService tokens;

    public AuthController(AppUserRepository users, PasswordEncoder passwords, JwtService tokens) {
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody Dtos.LoginRequest request) {
        Optional<AppUser> found = users.findByUsername(request.username());
        // Same response either way, so a caller cannot learn which usernames exist.
        if (found.isEmpty() || !passwords.matches(request.password(), found.get().getPasswordHash())) {
            return ResponseEntity.status(401)
                    .body(new Dtos.ApiError("unauthorized", "username or password is wrong", Instant.now()));
        }

        AppUser user = found.get();
        Instant now = Instant.now();
        return ResponseEntity.ok(new Dtos.LoginResponse(
                tokens.issue(user, now), user.getRole(), user.getReporterId(), now.plus(tokens.ttl())));
    }
}
