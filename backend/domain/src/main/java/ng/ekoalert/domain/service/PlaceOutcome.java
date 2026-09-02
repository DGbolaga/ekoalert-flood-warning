package ng.ekoalert.domain.service;

import ng.ekoalert.domain.model.DrainageEdge;
import ng.ekoalert.domain.model.ProposedPlace;
import ng.ekoalert.domain.model.Zone;

/**
 * What naming a place, or affirming somebody else's, did.
 *
 * @param place          the proposal as it stands after the tap
 * @param distinctVoices how many separate residents now say this place is real
 * @param promoted       whether this tap was the one that put it on the map
 * @param zone           the zone it became, or was merged into, once promoted
 * @param edge           the edge created from the origin zone to it, once promoted
 * @param mergedInto     true when the place turned out to be an existing zone
 *                       under a name residents actually use, rather than a new one
 */
public record PlaceOutcome(ProposedPlace place,
                           long distinctVoices,
                           boolean promoted,
                           Zone zone,
                           DrainageEdge edge,
                           boolean mergedInto) {

    /** A proposal still short of the threshold, or still missing its position. */
    static PlaceOutcome pending(ProposedPlace place, long voices) {
        return new PlaceOutcome(place, voices, false, null, null, false);
    }
}
