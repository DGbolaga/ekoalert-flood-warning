package ng.ekoalert.api.web;

import ng.ekoalert.domain.model.CorrectionAction;
import ng.ekoalert.domain.model.DrainageEdge;
import ng.ekoalert.domain.model.Zone;
import ng.ekoalert.domain.model.ZoneStatus;
import ng.ekoalert.domain.repo.DrainageEdgeRepository;
import ng.ekoalert.domain.repo.EdgeCorrectionRepository;
import ng.ekoalert.domain.repo.ZoneRepository;
import ng.ekoalert.domain.repo.ZoneStatusRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The map. Public: a warning nobody can read is not a warning. */
@RestController
@RequestMapping("/api/v1/zones")
public class ZoneController {

    private final ZoneRepository zones;
    private final ZoneStatusRepository statuses;
    private final DrainageEdgeRepository edges;
    private final EdgeCorrectionRepository corrections;

    public ZoneController(ZoneRepository zones,
                          ZoneStatusRepository statuses,
                          DrainageEdgeRepository edges,
                          EdgeCorrectionRepository corrections) {
        this.zones = zones;
        this.statuses = statuses;
        this.edges = edges;
        this.corrections = corrections;
    }

    @GetMapping
    public List<Dtos.ZoneSummary> all() {
        Map<String, ZoneStatus> byZone = statuses.findAll().stream()
                .collect(Collectors.toMap(ZoneStatus::getZoneId, Function.identity()));
        return zones.findAllByOrderByIdAsc().stream()
                .map(zone -> ViewMapper.zone(zone, byZone.get(zone.getId())))
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Dtos.ZoneDetail> one(@PathVariable String id) {
        Zone zone = zones.findById(id).orElse(null);
        if (zone == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new Dtos.ZoneDetail(
                ViewMapper.zone(zone, statuses.findById(id).orElse(null)),
                withVotes(edges.findByFromZoneOrderByIdAsc(id)),
                withVotes(edges.findByToZoneOrderByIdAsc(id))));
    }

    /**
     * Vote counts are attached here and not on the whole graph. Zone detail is
     * where a resident decides whether to tap confirm, so it is where the count
     * earns its query.
     */
    private List<Dtos.EdgeView> withVotes(List<DrainageEdge> found) {
        return found.stream()
                .map(edge -> ViewMapper.edge(edge,
                        corrections.countDistinctReporters(edge.getId(), CorrectionAction.CONFIRM.label()),
                        corrections.countDistinctReporters(edge.getId(), CorrectionAction.REJECT.label())))
                .toList();
    }
}
