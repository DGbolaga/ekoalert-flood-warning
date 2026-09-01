package ng.ekoalert.domain.service;

import ng.ekoalert.domain.model.Alert;
import ng.ekoalert.domain.model.AlertDeliveryRecord;
import ng.ekoalert.domain.model.Subscription;
import ng.ekoalert.domain.model.ZoneStatus;
import ng.ekoalert.domain.repo.AlertDeliveryRepository;
import ng.ekoalert.domain.repo.AlertRepository;
import ng.ekoalert.domain.repo.SubscriptionRepository;
import ng.ekoalert.domain.repo.ZoneStatusRepository;
import ng.ekoalert.engine.PropagatedAlert;
import ng.ekoalert.engine.PropagationConfig;
import ng.ekoalert.engine.PropagationEngine;
import ng.ekoalert.engine.Severity;
import ng.ekoalert.engine.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the engine for an escalated zone and decides what actually goes out.
 *
 * <p>Every propagated result becomes an alert row whether or not it is
 * delivered. Two things suppress delivery, and the order matters: the kill
 * switch first, then an unconfirmed path. An operator who has halted alerting
 * wants the log to say so, not to say the edge was inferred.
 */
@Service
public class EscalationService {

    private static final Logger log = LoggerFactory.getLogger(EscalationService.class);

    private final PropagationEngine engine;
    private final GraphService graphs;
    private final KillSwitchService killSwitch;
    private final AlertRepository alerts;
    private final AlertDeliveryRepository deliveries;
    private final SubscriptionRepository subscriptions;
    private final ZoneStatusRepository zoneStatuses;
    private final AlertPublisher publisher;
    private final AlertingProperties properties;

    public EscalationService(PropagationEngine engine,
                             GraphService graphs,
                             KillSwitchService killSwitch,
                             AlertRepository alerts,
                             AlertDeliveryRepository deliveries,
                             SubscriptionRepository subscriptions,
                             ZoneStatusRepository zoneStatuses,
                             AlertPublisher publisher,
                             AlertingProperties properties) {
        this.engine = engine;
        this.graphs = graphs;
        this.killSwitch = killSwitch;
        this.alerts = alerts;
        this.deliveries = deliveries;
        this.subscriptions = subscriptions;
        this.zoneStatuses = zoneStatuses;
        this.publisher = publisher;
        this.properties = properties;
    }

    @Transactional
    public EscalationOutcome escalate(String zoneId, Severity level, Instant at) {
        ZoneStatus status = zoneStatuses.findById(zoneId).orElseGet(() -> new ZoneStatus(zoneId));
        status.escalate(level, at);
        zoneStatuses.save(status);

        List<PropagatedAlert> propagated = engine.propagate(
                graphs.snapshot(),
                new ZoneId(zoneId),
                level,
                new PropagationConfig(properties.getMaxHops(), properties.isRequireConfirmedEdges()));

        boolean alertsEnabled = killSwitch.alertsEnabled();
        List<Alert> written = new ArrayList<>();

        for (PropagatedAlert result : propagated) {
            String suppressedBy = suppressionReason(alertsEnabled, result);
            Alert alert = alerts.save(new Alert(
                    zoneId,
                    result.target().value(),
                    result.level(),
                    result.etaMinutes(),
                    result.hops(),
                    at,
                    suppressedBy));
            written.add(alert);

            if (alert.wasDelivered()) {
                deliver(alert, at);
            }
        }

        publisher.zoneStatusChanged(zoneId, level, at);

        log.info("zone {} escalated to {} at {}: {} alert rows, {} delivered",
                zoneId, level, at, written.size(), written.stream().filter(Alert::wasDelivered).count());

        return new EscalationOutcome(zoneId, level, List.copyOf(written));
    }

    private String suppressionReason(boolean alertsEnabled, PropagatedAlert result) {
        if (!alertsEnabled) {
            return Alert.KILL_SWITCH;
        }
        if (!result.pathConfirmed()) {
            return Alert.INFERRED_EDGE;
        }
        return null;
    }

    private void deliver(Alert alert, Instant at) {
        for (Subscription subscription : subscriptions.findByZoneId(alert.getTargetZone())) {
            deliveries.save(new AlertDeliveryRecord(alert.getId(), subscription.getId(), at));
        }
        publisher.alertFired(alert);
    }
}
