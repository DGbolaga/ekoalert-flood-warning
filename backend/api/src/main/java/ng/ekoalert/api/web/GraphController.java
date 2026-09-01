package ng.ekoalert.api.web;

import ng.ekoalert.domain.model.ZoneStatus;
import ng.ekoalert.domain.repo.DrainageEdgeRepository;
import ng.ekoalert.domain.repo.ZoneRepository;
import ng.ekoalert.domain.repo.ZoneStatusRepository;
import ng.ekoalert.engine.Confidence;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The whole graph, for drawing.
 *
 * <p>Inferred edges are included. The map is complete on day one and almost
 * silent on day one; hiding the edges nobody has confirmed would hide exactly
 * the thing residents are being asked to correct.
 */
@RestController
@RequestMapping("/api/v1/graph")
public class GraphController {

    private final ZoneRepository zones;
    private final ZoneStatusRepository statuses;
    private final DrainageEdgeRepository edges;

    public GraphController(ZoneRepository zones, ZoneStatusRepository statuses, DrainageEdgeRepository edges) {
        this.zones = zones;
        this.statuses = statuses;
        this.edges = edges;
    }

    @GetMapping
    public Dtos.GraphResponse graph() {
        Map<String, ZoneStatus> byZone = statuses.findAll().stream()
                .collect(Collectors.toMap(ZoneStatus::getZoneId, Function.identity()));

        List<Dtos.ZoneSummary> zoneViews = zones.findAllByOrderByIdAsc().stream()
                .map(zone -> ViewMapper.zone(zone, byZone.get(zone.getId())))
                .toList();

        List<Dtos.EdgeView> edgeViews = edges.findAllByOrderByIdAsc().stream()
                .map(edge -> ViewMapper.edge(edge, null, null))
                .toList();

        return new Dtos.GraphResponse(zoneViews, edgeViews, new Dtos.GraphCounts(
                zoneViews.size(),
                edgeViews.size(),
                edgeViews.stream().filter(e -> e.confidence() == Confidence.INFERRED).count(),
                edgeViews.stream().filter(e -> e.confidence() == Confidence.CONFIRMED).count(),
                edgeViews.stream().filter(e -> e.confidence() == Confidence.REJECTED).count(),
                edgeViews.stream().filter(Dtos.EdgeView::blocked).count()));
    }
}
