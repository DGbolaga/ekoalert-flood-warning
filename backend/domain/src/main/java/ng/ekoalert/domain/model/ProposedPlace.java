package ng.ekoalert.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.locationtech.jts.geom.Point;

import java.time.Instant;

/**
 * A place a resident says water reaches, which the map does not have a node for.
 *
 * <p>Two things can be missing, and they are missing separately. The
 * {@code landmark} is what somebody called it, and is required, because a place
 * nobody can name is a place nobody can corroborate. The {@code location} is
 * where it actually is, and is optional, because the person who knows the name
 * is not always the person standing there. A place cannot be promoted until both
 * are known, which is why an affirmation is allowed to carry the GPS the
 * original proposal lacked.
 */
@Entity
@Table(name = "proposed_place")
public class ProposedPlace {

    public static final String PENDING = "pending";
    public static final String PROMOTED = "promoted";
    public static final String REJECTED = "rejected";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String landmark;

    @Column(columnDefinition = "geography(Point,4326)")
    private Point location;

    @Column(name = "from_zone", nullable = false)
    private String fromZone;

    @Column(name = "proposed_by")
    private Long proposedBy;

    @Column(name = "proposed_at", nullable = false)
    private Instant proposedAt = Instant.now();

    @Column(nullable = false)
    private String status = PENDING;

    @Column(name = "promoted_zone")
    private String promotedZone;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected ProposedPlace() {
    }

    public ProposedPlace(String landmark, Point location, String fromZone, Long proposedBy, Instant at) {
        this.landmark = landmark;
        this.location = location;
        this.fromZone = fromZone;
        this.proposedBy = proposedBy;
        this.proposedAt = at;
    }

    /** Nobody can be warned about a place whose position is unknown. */
    public boolean isLocated() {
        return location != null;
    }

    public boolean isPending() {
        return PENDING.equals(status);
    }

    public void promoteTo(String zoneId, Instant at) {
        this.promotedZone = zoneId;
        this.status = PROMOTED;
        this.resolvedAt = at;
    }

    public void reject(Instant at) {
        this.status = REJECTED;
        this.resolvedAt = at;
    }

    public Long getId() {
        return id;
    }

    public String getLandmark() {
        return landmark;
    }

    public Point getLocation() {
        return location;
    }

    public void setLocation(Point location) {
        this.location = location;
    }

    public String getFromZone() {
        return fromZone;
    }

    public Long getProposedBy() {
        return proposedBy;
    }

    public Instant getProposedAt() {
        return proposedAt;
    }

    public String getStatus() {
        return status;
    }

    public String getPromotedZone() {
        return promotedZone;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
