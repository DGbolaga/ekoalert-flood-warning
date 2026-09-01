package ng.ekoalert.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import ng.ekoalert.engine.Severity;

import java.time.Instant;

/**
 * One alert the engine produced for one target zone.
 *
 * <p>Rows are written whether or not they were delivered. {@code suppressedBy}
 * records why a row went nowhere, which is what makes the kill switch auditable
 * rather than merely effective.
 */
@Entity
@Table(name = "alert")
public class Alert {

    /** Suppression reasons. Kept as constants because the column is free text by schema. */
    public static final String KILL_SWITCH = "kill_switch";
    public static final String INFERRED_EDGE = "inferred_edge";
    public static final String REPLAY = "replay";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "origin_zone", nullable = false)
    private String originZone;

    @Column(name = "target_zone", nullable = false)
    private String targetZone;

    @Convert(converter = SeverityConverter.class)
    @Column(columnDefinition = "severity", nullable = false)
    private Severity level;

    @Column(name = "eta_minutes", nullable = false)
    private int etaMinutes;

    @Column(nullable = false)
    private int hops;

    @Column(name = "fired_at", nullable = false)
    private Instant firedAt = Instant.now();

    @Column(name = "suppressed_by")
    private String suppressedBy;

    protected Alert() {
    }

    public Alert(String originZone, String targetZone, Severity level,
                 int etaMinutes, int hops, Instant firedAt, String suppressedBy) {
        this.originZone = originZone;
        this.targetZone = targetZone;
        this.level = level;
        this.etaMinutes = etaMinutes;
        this.hops = hops;
        this.firedAt = firedAt;
        this.suppressedBy = suppressedBy;
    }

    public boolean wasDelivered() {
        return suppressedBy == null;
    }

    public Long getId() {
        return id;
    }

    public String getOriginZone() {
        return originZone;
    }

    public String getTargetZone() {
        return targetZone;
    }

    public Severity getLevel() {
        return level;
    }

    public int getEtaMinutes() {
        return etaMinutes;
    }

    public int getHops() {
        return hops;
    }

    public Instant getFiredAt() {
        return firedAt;
    }

    public String getSuppressedBy() {
        return suppressedBy;
    }
}
