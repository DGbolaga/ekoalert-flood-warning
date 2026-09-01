package ng.ekoalert.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import ng.ekoalert.engine.Confidence;
import ng.ekoalert.engine.Edge;
import ng.ekoalert.engine.ZoneId;

import java.time.Instant;

/**
 * A directed edge in the drainage graph, as stored.
 *
 * <p>Named to avoid colliding with {@link Edge}, the engine's value type. This
 * class is the mutable database row; that one is the immutable snapshot the
 * engine walks.
 */
@Entity
@Table(name = "edge")
public class DrainageEdge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "from_zone", nullable = false)
    private String fromZone;

    @Column(name = "to_zone", nullable = false)
    private String toZone;

    @Column(name = "travel_minutes", nullable = false)
    private int travelMinutes;

    @Column(name = "distance_m")
    private Integer distanceM;

    @Convert(converter = ConfidenceConverter.class)
    @Column(columnDefinition = "edge_confidence", nullable = false)
    private Confidence confidence = Confidence.INFERRED;

    @Column(nullable = false)
    private boolean blocked;

    @Column(name = "inference_basis")
    private String inferenceBasis;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected DrainageEdge() {
    }

    public DrainageEdge(String fromZone, String toZone, int travelMinutes,
                        Integer distanceM, String inferenceBasis) {
        this.fromZone = fromZone;
        this.toZone = toZone;
        this.travelMinutes = travelMinutes;
        this.distanceM = distanceM;
        this.inferenceBasis = inferenceBasis;
    }

    /** Projects this row into the engine's immutable value type. */
    public Edge toEngineEdge() {
        return new Edge(new ZoneId(fromZone), new ZoneId(toZone), travelMinutes, confidence, blocked);
    }

    public Long getId() {
        return id;
    }

    public String getFromZone() {
        return fromZone;
    }

    public String getToZone() {
        return toZone;
    }

    public int getTravelMinutes() {
        return travelMinutes;
    }

    public void setTravelMinutes(int travelMinutes) {
        this.travelMinutes = travelMinutes;
        touch();
    }

    public Integer getDistanceM() {
        return distanceM;
    }

    public Confidence getConfidence() {
        return confidence;
    }

    public void setConfidence(Confidence confidence) {
        this.confidence = confidence;
        touch();
    }

    public boolean isBlocked() {
        return blocked;
    }

    public void setBlocked(boolean blocked) {
        this.blocked = blocked;
        touch();
    }

    public String getInferenceBasis() {
        return inferenceBasis;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }
}
