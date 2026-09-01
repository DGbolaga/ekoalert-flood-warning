package ng.ekoalert.api.web;

import ng.ekoalert.domain.replay.ReplayRequest;
import ng.ekoalert.domain.replay.ReplayResult;
import ng.ekoalert.domain.replay.ReplayService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Run a past flood event against the graph and see what the engine would have
 * said. Writes nothing, delivers nothing.
 */
@RestController
@RequestMapping("/api/v1/replay")
public class ReplayController {

    private final ReplayService replay;

    public ReplayController(ReplayService replay) {
        this.replay = replay;
    }

    @PostMapping
    public ReplayResult replay(@RequestBody ReplayRequest request) {
        return replay.replay(request);
    }
}
