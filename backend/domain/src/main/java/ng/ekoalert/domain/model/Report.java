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

/** One reporter's observation of water in one zone. */
@Entity
@Table(name = "report")
public class Report {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "zone_id", nullable = false)
    private String zoneId;

    @Column(name = "reporter_id", nullable = false)
    private Long reporterId;

    @Convert(converter = SeverityConverter.class)
    @Column(columnDefinition = "severity", nullable = false)
    private Severity level;

    /** Optional one-tap field. Null means the reporter said nothing about the drain. */
    @Column(name = "drain_blocked")
    private Boolean drainBlocked;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Report() {
    }

    public Report(String zoneId, Long reporterId, Severity level, Boolean drainBlocked, Instant observedAt) {
        this.zoneId = zoneId;
        this.reporterId = reporterId;
        this.level = level;
        this.drainBlocked = drainBlocked;
        this.observedAt = observedAt;
    }

    public Long getId() {
        return id;
    }

    public String getZoneId() {
        return zoneId;
    }

    public Long getReporterId() {
        return reporterId;
    }

    public Severity getLevel() {
        return level;
    }

    public Boolean getDrainBlocked() {
        return drainBlocked;
    }

    public Instant getObservedAt() {
        return observedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
