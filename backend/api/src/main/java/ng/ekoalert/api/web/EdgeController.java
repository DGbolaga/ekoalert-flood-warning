package ng.ekoalert.api.web;

import jakarta.validation.Valid;
import ng.ekoalert.api.security.AuthenticatedUser;
import ng.ekoalert.domain.model.CorrectionAction;
import ng.ekoalert.domain.service.AlertingProperties;
import ng.ekoalert.domain.service.CorrectionOutcome;
import ng.ekoalert.domain.service.EdgeCorrectionService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * One tap, not a form. A form gets used by nobody.
 *
 * <p>Each of these is a single POST with no body beyond what the URL already
 * says, so the client can fire it straight from a tap on the map.
 */
@RestController
@RequestMapping("/api/v1/edges")
public class EdgeController {

    private final EdgeCorrectionService corrections;
    private final AlertingProperties properties;

    public EdgeController(EdgeCorrectionService corrections, AlertingProperties properties) {
        this.corrections = corrections;
        this.properties = properties;
    }

    @PostMapping("/{id}/confirm")
    public Dtos.CorrectionResponse confirm(@AuthenticationPrincipal AuthenticatedUser user,
                                           @PathVariable long id) {
        return respond(CorrectionAction.CONFIRM,
                corrections.confirm(id, user.reporterId(), Instant.now()));
    }

    @PostMapping("/{id}/reject")
    public Dtos.CorrectionResponse reject(@AuthenticationPrincipal AuthenticatedUser user,
                                          @PathVariable long id) {
        return respond(CorrectionAction.REJECT,
                corrections.reject(id, user.reporterId(), Instant.now()));
    }

    @PostMapping("/propose")
    public Dtos.CorrectionResponse propose(@AuthenticationPrincipal AuthenticatedUser user,
                                           @Valid @RequestBody Dtos.ProposeEdgeRequest request) {
        return respond(CorrectionAction.PROPOSE,
                corrections.propose(request.fromZone(), request.toZone(), user.reporterId(), Instant.now()));
    }

    private Dtos.CorrectionResponse respond(CorrectionAction action, CorrectionOutcome outcome) {
        return new Dtos.CorrectionResponse(
                outcome.correction().getId(),
                action.label(),
                outcome.correction().getFromZone(),
                outcome.correction().getToZone(),
                outcome.distinctVoices(),
                properties.getCorrectionThreshold(),
                outcome.thresholdMet(),
                outcome.edge() == null ? null : ViewMapper.edge(outcome.edge(), null, null));
    }
}
