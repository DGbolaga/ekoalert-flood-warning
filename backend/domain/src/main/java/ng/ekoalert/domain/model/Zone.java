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

    public static final String SEED = "seed";
    public static final String RESIDENT = "resident";

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

    /**
     * How this zone got here. A seeded zone was inferred from OSM waterway
     * geometry; a resident zone was named by somebody standing in it. The second
     * is the stronger provenance, and the pilot needs to be able to tell them
     * apart.
     */
    @Column(nullable = false)
    private String source = SEED;

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

    /**
     * The display label residents see. A surveyed name wins; failing that a
     * landmark a resident gave, which is the whole point of letting them name
     * places; failing both, the id, so the label is never invented.
     */
    public String displayName() {
        if (name != null && !name.isBlank()) return name;
        if (landmark != null && !landmark.isBlank()) return landmark;
        return id;
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

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
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
