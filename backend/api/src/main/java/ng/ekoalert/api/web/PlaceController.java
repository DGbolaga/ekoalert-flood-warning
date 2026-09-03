package ng.ekoalert.api.web;

import jakarta.validation.Valid;
import ng.ekoalert.api.security.AuthenticatedUser;
import ng.ekoalert.domain.model.ProposedPlace;
import ng.ekoalert.domain.repo.ZoneStatusRepository;
import ng.ekoalert.domain.service.AlertingProperties;
import ng.ekoalert.domain.service.PlaceOutcome;
import ng.ekoalert.domain.service.PlaceProposalService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Places residents have named that the graph has no node for.
 *
 * <p>Reading is public, because a pending place nobody can see is a place nobody
 * can corroborate, and corroboration is the whole mechanism. Writing needs a
 * reporter, like every other correction.
 */
@RestController
@RequestMapping("/api/v1/places")
public class PlaceController {

    private final PlaceProposalService places;
    private final ZoneStatusRepository statuses;
    private final AlertingProperties properties;

    public PlaceController(PlaceProposalService places,
                           ZoneStatusRepository statuses,
                           AlertingProperties properties) {
        this.places = places;
        this.statuses = statuses;
        this.properties = properties;
    }

    /** Every place still waiting on voices or on a position. */
    @GetMapping
    public List<Dtos.PlaceView> pending() {
        return places.pending().stream()
                .map(place -> ViewMapper.place(place, places.voiceCount(place.getId()),
                        properties.getCorrectionThreshold(), null, null, false))
                .toList();
    }

    @PostMapping("/propose")
    public Dtos.PlaceView propose(@AuthenticationPrincipal AuthenticatedUser user,
                                  @Valid @RequestBody Dtos.ProposePlaceRequest request) {
        return respond(places.propose(request.landmark(), request.lat(), request.lng(),
                request.fromZone(), user.reporterId(), Instant.now()));
    }

    @PostMapping("/{id}/affirm")
    public Dtos.PlaceView affirm(@AuthenticationPrincipal AuthenticatedUser user,
                                 @PathVariable long id,
                                 @RequestBody(required = false) Dtos.AffirmPlaceRequest request) {
        Double lat = request == null ? null : request.lat();
        Double lng = request == null ? null : request.lng();
        return respond(places.affirm(id, lat, lng, user.reporterId(), Instant.now()));
    }

    private Dtos.PlaceView respond(PlaceOutcome outcome) {
        ProposedPlace place = outcome.place();
        Dtos.ZoneSummary zone = outcome.zone() == null ? null
                : ViewMapper.zone(outcome.zone(),
                        statuses.findById(outcome.zone().getId()).orElse(null));
        Dtos.EdgeView edge = outcome.edge() == null ? null
                : ViewMapper.edge(outcome.edge(), null, null);
        return ViewMapper.place(place, outcome.distinctVoices(),
                properties.getCorrectionThreshold(), zone, edge, outcome.mergedInto());
    }
}
