package ng.ekoalert.api.web;

import jakarta.validation.Valid;
import ng.ekoalert.api.security.AuthenticatedUser;
import ng.ekoalert.domain.service.ReportOutcome;
import ng.ekoalert.domain.service.ReportService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    @PostMapping
    public ResponseEntity<Dtos.ReportResponse> file(@AuthenticationPrincipal AuthenticatedUser user,
                                                    @Valid @RequestBody Dtos.ReportRequest request) {
        if (user.reporterId() == null) {
            throw new IllegalArgumentException("this login is not linked to a reporter");
        }

        Instant observedAt = request.observedAt() != null ? request.observedAt() : Instant.now();
        ReportOutcome outcome = reports.file(
                user.reporterId(), request.zoneId(), request.level(), request.drainBlocked(), observedAt);

        List<Dtos.AlertView> alerts = outcome.escalation()
                .map(escalation -> escalation.alerts().stream().map(ViewMapper::alert).toList())
                .orElse(List.of());

        return ResponseEntity.status(201).body(new Dtos.ReportResponse(
                outcome.report().getId(),
                outcome.report().getZoneId(),
                outcome.report().getLevel(),
                outcome.report().getObservedAt(),
                outcome.quorumLevel().isPresent(),
                outcome.quorumLevel().orElse(null),
                outcome.escalation().isPresent(),
                alerts));
    }
}
