package ng.ekoalert.domain.service;

import ng.ekoalert.domain.model.CorrectionAction;
import ng.ekoalert.domain.model.DrainageEdge;
import ng.ekoalert.domain.model.EdgeCorrection;
import ng.ekoalert.domain.model.Zone;
import ng.ekoalert.domain.repo.DrainageEdgeRepository;
import ng.ekoalert.domain.repo.EdgeCorrectionRepository;
import ng.ekoalert.domain.repo.ZoneRepository;
import ng.ekoalert.engine.Confidence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Residents correcting the graph, one tap at a time.
 *
 * <p>Two rules shape this class. An edge changes state on a threshold of
 * distinct residents, never on one vote. And every tap is logged whether or not
 * it changed anything, because the log is the evidence trail the pilot exists to
 * produce.
 */
@Service
public class EdgeCorrectionService {

    private static final Logger log = LoggerFactory.getLogger(EdgeCorrectionService.class);

    private final DrainageEdgeRepository edges;
    private final EdgeCorrectionRepository corrections;
    private final ZoneRepository zones;
    private final AlertingProperties properties;

    public EdgeCorrectionService(DrainageEdgeRepository edges,
                                 EdgeCorrectionRepository corrections,
                                 ZoneRepository zones,
                                 AlertingProperties properties) {
        this.edges = edges;
        this.corrections = corrections;
        this.zones = zones;
        this.properties = properties;
    }

    @Transactional
    public CorrectionOutcome confirm(long edgeId, Long reporterId, Instant at) {
        return vote(edgeId, reporterId, at, CorrectionAction.CONFIRM, Confidence.CONFIRMED);
    }

    @Transactional
    public CorrectionOutcome reject(long edgeId, Long reporterId, Instant at) {
        return vote(edgeId, reporterId, at, CorrectionAction.REJECT, Confidence.REJECTED);
    }

    private CorrectionOutcome vote(long edgeId, Long reporterId, Instant at,
                                   CorrectionAction action, Confidence target) {
        DrainageEdge edge = edges.findById(edgeId)
                .orElseThrow(() -> new IllegalArgumentException("unknown edge: " + edgeId));

        EdgeCorrection correction = corrections.save(new EdgeCorrection(
                edge.getId(), edge.getFromZone(), edge.getToZone(), reporterId, action, at));

        long voices = corrections.countDistinctReporters(edge.getId(), action.label());
        boolean crossed = voices >= properties.getCorrectionThreshold() && edge.getConfidence() != target;
        if (crossed) {
            edge.setConfidence(target);
            edges.save(edge);
            log.info("edge {} to {} is now {} on {} distinct voices",
                    edge.getFromZone(), edge.getToZone(), target, voices);
        }

        return new CorrectionOutcome(correction, edge, voices, crossed, edge.getConfidence());
    }

    /**
     * A resident proposing an edge the seed never inferred. These are the
     * junction edges between corridors, deliberately left blank because
     * inference is worst at exactly the edges that matter most.
     *
     * <p>The edge is created only once enough separate residents have proposed
     * it, and it is created {@code inferred} like any other. Proposing it is not
     * the same as confirming it.
     */
    @Transactional
    public CorrectionOutcome propose(String fromZone, String toZone, Long reporterId, Instant at) {
        if (fromZone.equals(toZone)) {
            throw new IllegalArgumentException("an edge cannot start and end in the same zone: " + fromZone);
        }
        Zone from = zones.findById(fromZone)
                .orElseThrow(() -> new IllegalArgumentException("unknown zone: " + fromZone));
        Zone to = zones.findById(toZone)
                .orElseThrow(() -> new IllegalArgumentException("unknown zone: " + toZone));

        DrainageEdge existing = edges.findByFromZoneAndToZone(fromZone, toZone).orElse(null);
        EdgeCorrection correction = corrections.save(new EdgeCorrection(
                existing != null ? existing.getId() : null,
                fromZone, toZone, reporterId, CorrectionAction.PROPOSE, at));

        if (existing != null) {
            // Already on the map. Nothing to create; the tap is still recorded.
            return new CorrectionOutcome(correction, existing, 0, false, existing.getConfidence());
        }

        long voices = corrections.countDistinctReportersForPair(
                fromZone, toZone, CorrectionAction.PROPOSE.label());
        if (voices < properties.getCorrectionThreshold()) {
            return new CorrectionOutcome(correction, null, voices, false, null);
        }

        DrainageEdge created = edges.save(buildProposedEdge(from, to));
        log.info("edge {} to {} created from {} resident proposals, seeded inferred",
                fromZone, toZone, voices);
        return new CorrectionOutcome(correction, created, voices, true, created.getConfidence());
    }

    private DrainageEdge buildProposedEdge(Zone from, Zone to) {
        int distance = (int) Math.round(Geo.metresBetween(from.getLocation(), to.getLocation()));
        int minutes = Math.max(properties.getMinTravelMinutes(),
                Math.round((float) distance / properties.getFlowRateMetersPerMinute()));
        return new DrainageEdge(from.getId(), to.getId(), minutes, distance,
                "resident proposal, timing estimated at "
                        + properties.getFlowRateMetersPerMinute() + " m/min pending observation");
    }
}
