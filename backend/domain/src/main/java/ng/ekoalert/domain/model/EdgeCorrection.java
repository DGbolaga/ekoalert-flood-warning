package ng.ekoalert.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One resident correcting the graph.
 *
 * <p>Every correction is logged with who and when. This is the project's
 * evidence trail and the most interesting data the pilot will produce, so rows
 * here are append only: nothing in the system updates or deletes one.
 */
@Entity
@Table(name = "edge_correction")
public class EdgeCorrection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null for a proposed edge that does not exist yet. */
    @Column(name = "edge_id")
    private Long edgeId;

    @Column(name = "from_zone", nullable = false)
    private String fromZone;

    @Column(name = "to_zone", nullable = false)
    private String toZone;

    @Column(name = "reporter_id")
    private Long reporterId;

    @Column(nullable = false)
    private String action;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected EdgeCorrection() {
    }

    public EdgeCorrection(Long edgeId, String fromZone, String toZone,
                          Long reporterId, CorrectionAction action, Instant createdAt) {
        this.edgeId = edgeId;
        this.fromZone = fromZone;
        this.toZone = toZone;
        this.reporterId = reporterId;
        this.action = action.label();
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getEdgeId() {
        return edgeId;
    }

    public String getFromZone() {
        return fromZone;
    }

    public String getToZone() {
        return toZone;
    }

    public Long getReporterId() {
        return reporterId;
    }

    public CorrectionAction getAction() {
        return CorrectionAction.fromLabel(action);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
