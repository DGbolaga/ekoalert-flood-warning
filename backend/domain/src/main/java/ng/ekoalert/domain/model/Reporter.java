package ng.ekoalert.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A vetted community reporter. Only verified, unsuspended reporters count toward quorum. */
@Entity
@Table(name = "reporter")
public class Reporter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "zone_id", nullable = false)
    private String zoneId;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(nullable = false, unique = true)
    private String phone;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(nullable = false)
    private boolean suspended;

    protected Reporter() {
    }

    public Reporter(String zoneId, String displayName, String phone) {
        this.zoneId = zoneId;
        this.displayName = displayName;
        this.phone = phone;
    }

    /** Whether this reporter's reports count toward quorum. */
    public boolean countsTowardQuorum() {
        return verifiedAt != null && !suspended;
    }

    public Long getId() {
        return id;
    }

    public String getZoneId() {
        return zoneId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getPhone() {
        return phone;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public void verify(Instant at) {
        this.verifiedAt = at;
    }

    /**
     * Undo the vetting itself, as distinct from suspending. A suspension says
     * this person is set aside for now; revoking says the vetting was wrong.
     * Both stop the reports counting, and the reports themselves are kept
     * either way.
     */
    public void revokeVerification() {
        this.verifiedAt = null;
    }

    public boolean isSuspended() {
        return suspended;
    }

    public void setSuspended(boolean suspended) {
        this.suspended = suspended;
    }
}
