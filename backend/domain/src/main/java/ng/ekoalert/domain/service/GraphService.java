package ng.ekoalert.domain.service;

import ng.ekoalert.domain.model.DrainageEdge;
import ng.ekoalert.domain.repo.DrainageEdgeRepository;
import ng.ekoalert.engine.DrainageGraph;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Reads the stored graph into the immutable snapshot the engine walks. */
@Service
public class GraphService {

    private final DrainageEdgeRepository edges;

    public GraphService(DrainageEdgeRepository edges) {
        this.edges = edges;
    }

    @Transactional(readOnly = true)
    public DrainageGraph snapshot() {
        return new DrainageGraph(edges.findAllByOrderByIdAsc().stream()
                .map(DrainageEdge::toEngineEdge)
                .toList());
    }

    @Transactional(readOnly = true)
    public List<DrainageEdge> allEdges() {
        return edges.findAllByOrderByIdAsc();
    }
}
