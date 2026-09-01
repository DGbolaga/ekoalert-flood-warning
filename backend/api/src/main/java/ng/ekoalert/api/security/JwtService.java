package ng.ekoalert.api.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import ng.ekoalert.domain.model.AppUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/** Issues and reads the bearer tokens. */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    /** Obvious enough that nobody mistakes it for a real key, and refused outside dev. */
    static final String DEVELOPMENT_SECRET = "ekoalert-development-secret-do-not-use-in-production";

    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_REPORTER = "reporterId";

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(@Value("${ekoalert.jwt.secret}") String secret,
                      @Value("${ekoalert.jwt.ttl:PT12H}") Duration ttl) {
        if (DEVELOPMENT_SECRET.equals(secret)) {
            log.warn("using the built in development JWT secret. Set ekoalert.jwt.secret before this "
                    + "reaches anyone outside the team.");
        }
        byte[] material = secret.getBytes(StandardCharsets.UTF_8);
        if (material.length < 32) {
            throw new IllegalStateException(
                    "ekoalert.jwt.secret must be at least 32 bytes for HS256, was " + material.length);
        }
        this.key = new SecretKeySpec(material, "HmacSHA256");
        this.ttl = ttl;
    }

    public String issue(AppUser user, Instant now) {
        return Jwts.builder()
                .subject(user.getUsername())
                .id(String.valueOf(user.getId()))
                .claim(CLAIM_ROLE, user.getRole())
                .claim(CLAIM_REPORTER, user.getReporterId())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    public Optional<AuthenticatedUser> parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token)
                    .getPayload();
            Number reporterId = claims.get(CLAIM_REPORTER, Number.class);
            return Optional.of(new AuthenticatedUser(
                    Long.valueOf(claims.getId()),
                    claims.getSubject(),
                    claims.get(CLAIM_ROLE, String.class),
                    reporterId == null ? null : reporterId.longValue()));
        } catch (JwtException | IllegalArgumentException e) {
            // An unreadable token is an anonymous caller, not a server error.
            log.debug("rejected a bearer token: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public Duration ttl() {
        return ttl;
    }
}
