package ng.ekoalert.domain.service;

import ng.ekoalert.domain.model.Alert;
import ng.ekoalert.engine.Severity;

import java.util.List;

/**
 * What one escalation produced.
 *
 * @param alerts every alert row written, delivered or not
 */
public record EscalationOutcome(String originZone,
                                Severity level,
                                List<Alert> alerts) {

    public List<Alert> delivered() {
        return alerts.stream().filter(Alert::wasDelivered).toList();
    }

    public List<Alert> suppressed() {
        return alerts.stream().filter(alert -> !alert.wasDelivered()).toList();
    }
}
