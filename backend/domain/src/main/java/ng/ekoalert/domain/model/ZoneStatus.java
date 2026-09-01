package ng.ekoalert.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import ng.ekoalert.engine.Severity;

import java.time.Instant;

/** Current flooding state of a zone. Absent or cleared means dry as far as the system knows. */
@Entity
@Table(name = "zone_status")
public class ZoneStatus {

    @Id
    @Column(name = "zone_id")
    private String zoneId;

    @Convert(converter = SeverityConverter.class)
    @Column(columnDefinition = "severity")
    private Severity level;

    @Column(name = "escalated_at")
    private Instant escalatedAt;

    @Column(name = "cleared_at")
    private Instant clearedAt;

    protected ZoneStatus() {
    }

    public ZoneStatus(String zoneId) {
        this.zoneId = zoneId;
    }

    public boolean isActive() {
        return escalatedAt != null && clearedAt == null;
    }

    public void escalate(Severity level, Instant at) {
        this.level = level;
        this.escalatedAt = at;
        this.clearedAt = null;
    }

    public void clear(Instant at) {
        this.clearedAt = at;
        this.level = null;
    }

    public String getZoneId() {
        return zoneId;
    }

    public Severity getLevel() {
        return level;
    }

    public Instant getEscalatedAt() {
        return escalatedAt;
    }

    public Instant getClearedAt() {
        return clearedAt;
    }
}
