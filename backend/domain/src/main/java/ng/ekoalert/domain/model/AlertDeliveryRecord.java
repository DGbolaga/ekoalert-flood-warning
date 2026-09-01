package ng.ekoalert.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Proof that one alert reached one subscriber.
 *
 * <p>The all-clear goes to everyone who received an alert, so it is driven off
 * these rows. A suppressed alert produces none, and therefore produces no
 * all-clear either.
 */
@Entity
@Table(name = "alert_delivery")
public class AlertDeliveryRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "alert_id", nullable = false)
    private Long alertId;

    @Column(name = "subscription_id", nullable = false)
    private Long subscriptionId;

    @Column(name = "delivered_at", nullable = false)
    private Instant deliveredAt = Instant.now();

    protected AlertDeliveryRecord() {
    }

    public AlertDeliveryRecord(Long alertId, Long subscriptionId, Instant deliveredAt) {
        this.alertId = alertId;
        this.subscriptionId = subscriptionId;
        this.deliveredAt = deliveredAt;
    }

    public Long getId() {
        return id;
    }

    public Long getAlertId() {
        return alertId;
    }

    public Long getSubscriptionId() {
        return subscriptionId;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }
}
