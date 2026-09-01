package ng.ekoalert.api.web;

import jakarta.validation.Valid;
import ng.ekoalert.domain.model.Reporter;
import ng.ekoalert.domain.repo.ReporterRepository;
import ng.ekoalert.domain.service.KillSwitchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** Admin controls. The kill switch is the one that matters. */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final KillSwitchService killSwitch;
    private final ReporterRepository reporters;

    public AdminController(KillSwitchService killSwitch, ReporterRepository reporters) {
        this.killSwitch = killSwitch;
        this.reporters = reporters;
    }

    @PostMapping("/kill-switch")
    public Dtos.KillSwitchResponse killSwitch(@Valid @RequestBody Dtos.KillSwitchRequest request) {
        killSwitch.setAlertsEnabled(request.enabled());
        return new Dtos.KillSwitchResponse(killSwitch.alertsEnabled(), Instant.now());
    }

    @PostMapping("/reporters/{id}/suspend")
    public ResponseEntity<Dtos.ReporterView> suspend(@PathVariable long id,
                                                     @RequestBody(required = false) Dtos.SuspendRequest request) {
        Reporter reporter = reporters.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("unknown reporter: " + id));
        // Defaults to suspending. Lifting a suspension takes an explicit false.
        reporter.setSuspended(request == null || request.suspended() == null || request.suspended());
        reporters.save(reporter);
        return ResponseEntity.ok(ViewMapper.reporter(reporter));
    }
}
