package ng.ekoalert.domain.service;

import ng.ekoalert.domain.model.Report;
import ng.ekoalert.engine.Severity;

import java.util.Optional;

/**
 * What filing one report led to.
 *
 * @param quorumLevel the level a quorum supported, empty when one report is not enough
 * @param escalation  present only when this report actually escalated the zone
 */
public record ReportOutcome(Report report,
                            Optional<Severity> quorumLevel,
                            Optional<EscalationOutcome> escalation) {
}
