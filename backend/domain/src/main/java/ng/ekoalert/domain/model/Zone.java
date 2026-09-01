package ng.ekoalert.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import ng.ekoalert.engine.ZoneId;
import org.locationtech.jts.geom.Point;

import java.time.Instant;

/**
 * A zone: a street cluster, an estate, a ward.
 *
 * <p>{@code name} and {@code landmark} stay null until a field survey fills
 * them. They are never populated by inference. Everything downstream must work
 * with them blank.
 */
@Entity
@Table(name = "zone")
public class Zone {

    @Id
    private String id;

    @Column(nullable = false)
    private String corridor;

    private String name;

    private String landmark;

    @Column(columnDefinition = "geography(Point,4326)", nullable = false)
    private Point location;

    @Column(name = "needs_field_naming", nullable = false)
    private boolean needsFieldNaming;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Zone() {
    }

    public Zone(String id, String corridor, Point location, boolean needsFieldNaming) {
        this.id = id;
        this.corridor = corridor;
        this.location = location;
        this.needsFieldNaming = needsFieldNaming;
    }

    public ZoneId zoneId() {
        return new ZoneId(id);
    }

    /** The display label residents see. Falls back to the id while the survey is outstanding. */
    public String displayName() {
        return name != null && !name.isBlank() ? name : id;
    }

    public String getId() {
        return id;
    }

    public String getCorridor() {
        return corridor;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getLandmark() {
        return landmark;
    }

    public void setLandmark(String landmark) {
        this.landmark = landmark;
    }

    public Point getLocation() {
        return location;
    }

    public void setLocation(Point location) {
        this.location = location;
    }

    public boolean isNeedsFieldNaming() {
        return needsFieldNaming;
    }

    public void setNeedsFieldNaming(boolean needsFieldNaming) {
        this.needsFieldNaming = needsFieldNaming;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
