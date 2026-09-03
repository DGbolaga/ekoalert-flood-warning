package ng.ekoalert.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One person saying a proposed place is real. The table has a unique constraint
 * on (place, reporter), so the threshold counts people even if somebody taps
 * twice.
 */
@Entity
@Table(name = "proposed_place_voice")
public class ProposedPlaceVoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "place_id", nullable = false)
    private Long placeId;

    @Column(name = "reporter_id", nullable = false)
    private Long reporterId;

    /** Whether this voice was the one that supplied the GPS fix. */
    @Column(nullable = false)
    private boolean located;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ProposedPlaceVoice() {
    }

    public ProposedPlaceVoice(Long placeId, Long reporterId, boolean located, Instant at) {
        this.placeId = placeId;
        this.reporterId = reporterId;
        this.located = located;
        this.createdAt = at;
    }

    /** He has now supplied the position the place was missing. */
    public void markLocated() {
        this.located = true;
    }

    public Long getId() {
        return id;
    }

    public Long getPlaceId() {
        return placeId;
    }

    public Long getReporterId() {
        return reporterId;
    }

    public boolean isLocated() {
        return located;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
