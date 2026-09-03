package ng.ekoalert.domain.service;

import ng.ekoalert.domain.model.DrainageEdge;
import ng.ekoalert.domain.model.Zone;

/**
 * One definition of an edge that came from residents rather than from the seed.
 *
 * <p>It is created inferred, so it warns nobody, and its timing is a placeholder
 * at the provisional surface flow rate until an observed event replaces it. Both
 * the resident who proposes an edge between two known zones and the resident who
 * names an entirely new place downstream end up here, because the claim they are
 * making is the same claim.
 */
final class ProposedEdges {

    private ProposedEdges() {
    }

    static DrainageEdge inferred(Zone from, Zone to, AlertingProperties properties) {
        int distance = (int) Math.round(Geo.metresBetween(from.getLocation(), to.getLocation()));
        int minutes = Math.max(properties.getMinTravelMinutes(),
                Math.round((float) distance / properties.getFlowRateMetersPerMinute()));
        return new DrainageEdge(from.getId(), to.getId(), minutes, distance,
                "resident proposal, timing estimated at "
                        + properties.getFlowRateMetersPerMinute() + " m/min pending observation");
    }
}
