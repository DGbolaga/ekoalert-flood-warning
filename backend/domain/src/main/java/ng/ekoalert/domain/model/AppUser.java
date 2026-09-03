package ng.ekoalert.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A login. Reporters and admins authenticate; map and graph reads do not. */
@Entity
@Table(name = "app_user")
public class AppUser {

    public static final String ROLE_REPORTER = "REPORTER";
    public static final String ROLE_ADMIN = "ADMIN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String role;

    /** Set for reporters, null for admins. Links the login to the field identity. */
    @Column(name = "reporter_id")
    private Long reporterId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected AppUser() {
    }

    public AppUser(String username, String passwordHash, String role, Long reporterId) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.reporterId = reporterId;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    /** Only ever a fresh hash. The plain password is shown once and not stored. */
    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getRole() {
        return role;
    }

    public Long getReporterId() {
        return reporterId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
