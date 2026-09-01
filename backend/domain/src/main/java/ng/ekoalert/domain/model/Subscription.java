package ng.ekoalert.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A resident asking to hear about one zone. */
@Entity
@Table(name = "subscription")
public class Subscription {

    public static final String CHANNEL_SSE = "sse";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "zone_id", nullable = false)
    private String zoneId;

    @Column(nullable = false)
    private String channel;

    @Column(nullable = false)
    private String address;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Subscription() {
    }

    public Subscription(String zoneId, String channel, String address) {
        this.zoneId = zoneId;
        this.channel = channel;
        this.address = address;
    }

    public Long getId() {
        return id;
    }

    public String getZoneId() {
        return zoneId;
    }

    public String getChannel() {
        return channel;
    }

    public String getAddress() {
        return address;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
