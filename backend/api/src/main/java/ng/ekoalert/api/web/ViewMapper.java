package ng.ekoalert.api.web;

import ng.ekoalert.domain.model.Alert;
import ng.ekoalert.domain.model.DrainageEdge;
import ng.ekoalert.domain.model.ProposedPlace;
import ng.ekoalert.domain.model.Reporter;
import ng.ekoalert.domain.model.Zone;
import ng.ekoalert.domain.model.ZoneStatus;
import ng.ekoalert.engine.Confidence;

/** Entities to wire shapes. One place, so the derived fields stay derived in one place. */
final class ViewMapper {

    private ViewMapper() {
    }

    static Dtos.ZoneSummary zone(Zone zone, ZoneStatus status) {
        return new Dtos.ZoneSummary(
                zone.getId(),
                zone.getCorridor(),
                zone.getName(),
                zone.getLandmark(),
                zone.displayName(),
                zone.getLocation().getY(),
                zone.getLocation().getX(),
                zone.isNeedsFieldNaming(),
                status == null ? Dtos.ZoneStatusView.dry()
                        : new Dtos.ZoneStatusView(status.getLevel(), status.getEscalatedAt(),
                        status.getClearedAt(), status.isActive()));
    }

    static Dtos.EdgeView edge(DrainageEdge edge, Long confirmations, Long rejections) {
        return new Dtos.EdgeView(
                edge.getId(),
                edge.getFromZone(),
                edge.getToZone(),
                edge.getTravelMinutes(),
                edge.getDistanceM(),
                edge.getConfidence(),
                edge.isBlocked(),
                // Alertable is derived, never stored. Only a confirmed, unblocked
                // edge carries an alert.
                edge.getConfidence() == Confidence.CONFIRMED && !edge.isBlocked(),
                edge.getInferenceBasis(),
                edge.getUpdatedAt(),
                confirmations,
                rejections);
    }

    /**
     * A pending place has no zone and no edge yet, and may have no position
     * either. Everything the map needs to draw it as provisional is on the wire,
     * so the client never has to guess which of those is missing.
     */
    static Dtos.PlaceView place(ProposedPlace place, long voices, long threshold,
                                Dtos.ZoneSummary zone, Dtos.EdgeView edge, boolean mergedInto) {
        return new Dtos.PlaceView(
                place.getId(),
                place.getLandmark(),
                place.isLocated() ? place.getLocation().getY() : null,
                place.isLocated() ? place.getLocation().getX() : null,
                place.isLocated(),
                place.getFromZone(),
                place.getStatus(),
                voices,
                threshold,
                ProposedPlace.PROMOTED.equals(place.getStatus()),
                mergedInto,
                place.getProposedAt(),
                zone,
                edge);
    }

    static Dtos.AlertView alert(Alert alert) {
        return new Dtos.AlertView(
                alert.getId(),
                alert.getOriginZone(),
                alert.getTargetZone(),
                alert.getLevel(),
                alert.getEtaMinutes(),
                alert.getHops(),
                alert.getFiredAt(),
                alert.getSuppressedBy());
    }

    static Dtos.ReporterView reporter(Reporter reporter) {
        return new Dtos.ReporterView(
                reporter.getId(),
                reporter.getZoneId(),
                reporter.getDisplayName(),
                reporter.isSuspended(),
                reporter.getVerifiedAt());
    }
}
