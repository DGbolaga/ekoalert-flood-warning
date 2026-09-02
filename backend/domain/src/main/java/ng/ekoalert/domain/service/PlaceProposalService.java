package ng.ekoalert.domain.service;

import ng.ekoalert.domain.model.DrainageEdge;
import ng.ekoalert.domain.model.ProposedPlace;
import ng.ekoalert.domain.model.ProposedPlaceVoice;
import ng.ekoalert.domain.model.Zone;
import ng.ekoalert.domain.repo.DrainageEdgeRepository;
import ng.ekoalert.domain.repo.ProposedPlaceRepository;
import ng.ekoalert.domain.repo.ProposedPlaceVoiceRepository;
import ng.ekoalert.domain.repo.ZoneRepository;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Residents adding the nodes the map is missing.
 *
 * <p>Correcting an edge assumes both of its ends already exist. Often neither
 * does: the pilot corridor stops at 20 seeded zones and water does not, so the
 * honest answer to "where does it go next" is frequently a place the graph has
 * never heard of. This is where that answer goes.
 *
 * <p>The same two rules that govern edges govern places. Nothing reaches the
 * live graph on one person's say-so, and every tap is logged with who and when.
 * A promoted place arrives with an inferred edge, so naming a place warns
 * nobody until the edge to it is separately confirmed.
 */
@Service
public class PlaceProposalService {

    private static final Logger log = LoggerFactory.getLogger(PlaceProposalService.class);

    private static final GeometryFactory GEOMETRY =
            new GeometryFactory(new PrecisionModel(), 4326);

    /** Seeded ids look like Z01. New ones continue the same run rather than
        inventing a second scheme residents would have to learn. */
    private static final Pattern ZONE_ID = Pattern.compile("Z(\\d+)");

    private final ProposedPlaceRepository places;
    private final ProposedPlaceVoiceRepository voices;
    private final ZoneRepository zones;
    private final DrainageEdgeRepository edges;
    private final AlertingProperties properties;

    public PlaceProposalService(ProposedPlaceRepository places,
                                ProposedPlaceVoiceRepository voices,
                                ZoneRepository zones,
                                DrainageEdgeRepository edges,
                                AlertingProperties properties) {
        this.places = places;
        this.voices = voices;
        this.zones = zones;
        this.edges = edges;
        this.properties = properties;
    }

    /**
     * A resident naming somewhere water reaches that the map has no node for.
     *
     * <p>The position is optional on purpose. The person who knows a place is
     * called Alapere Bus Stop is not always the person standing at it, and
     * refusing the name until somebody produces a GPS fix would lose the harder
     * half of the information to keep the easier half tidy.
     */
    @Transactional
    public PlaceOutcome propose(String landmark, Double lat, Double lng,
                                String fromZone, Long reporterId, Instant at) {
        String cleaned = clean(landmark);
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("a place needs a name somebody would recognise");
        }
        zones.findById(fromZone)
                .orElseThrow(() -> new IllegalArgumentException("unknown zone: " + fromZone));

        Point location = pointOrNull(lat, lng);
        if (location != null) {
            requirePlausible(zones.findById(fromZone).orElseThrow(), location);
        }
        ProposedPlace place = places.save(
                new ProposedPlace(cleaned, location, fromZone, reporterId, at));
        // The proposer is the first voice. Saying it counts as standing behind it.
        voices.save(new ProposedPlaceVoice(place.getId(), reporterId, location != null, at));

        log.info("place '{}' proposed downstream of {} by reporter {}{}",
                cleaned, fromZone, reporterId, location == null ? " (position unknown)" : "");
        return settle(place, at);
    }

    /**
     * A second resident saying somebody else's place is real.
     *
     * <p>An affirmation may carry the GPS fix the original proposal lacked,
     * which is the common case: one person knows the name, another is standing
     * there.
     */
    @Transactional
    public PlaceOutcome affirm(long placeId, Double lat, Double lng, Long reporterId, Instant at) {
        ProposedPlace place = places.findById(placeId)
                .orElseThrow(() -> new IllegalArgumentException("unknown place: " + placeId));

        Point supplied = pointOrNull(lat, lng);
        boolean located = false;
        if (!place.isLocated() && supplied != null) {
            requirePlausible(zones.findById(place.getFromZone()).orElseThrow(), supplied);
            place.setLocation(supplied);
            places.save(place);
            located = true;
            log.info("place {} '{}' located by reporter {}", placeId, place.getLandmark(), reporterId);
        }

        // One row per person. A second tap from the same person is recorded as
        // having happened and changes nothing, exactly like a repeated confirm.
        ProposedPlaceVoice existing = voices.findByPlaceIdAndReporterId(placeId, reporterId).orElse(null);
        if (existing == null) {
            voices.save(new ProposedPlaceVoice(placeId, reporterId, located, at));
        } else if (located) {
            // He had already spoken for this place and has now supplied the
            // position it was missing. The credit belongs on his existing voice.
            existing.markLocated();
            voices.save(existing);
        }
        return settle(place, at);
    }

    /** An admin discarding a proposal. The row stays, because the log is the point. */
    @Transactional
    public PlaceOutcome reject(long placeId, Instant at) {
        ProposedPlace place = places.findById(placeId)
                .orElseThrow(() -> new IllegalArgumentException("unknown place: " + placeId));
        place.reject(at);
        places.save(place);
        return PlaceOutcome.pending(place, voices.countByPlaceId(placeId));
    }

    @Transactional(readOnly = true)
    public List<ProposedPlace> pending() {
        return places.findByStatusOrderByIdAsc(ProposedPlace.PENDING);
    }

    @Transactional(readOnly = true)
    public long voiceCount(long placeId) {
        return voices.countByPlaceId(placeId);
    }

    /**
     * Promote if it has earned it. Two conditions, not one: enough separate
     * people, and a position, because a zone whose location nobody knows cannot
     * be drawn, timed, or warned about.
     */
    private PlaceOutcome settle(ProposedPlace place, Instant at) {
        long count = voices.countByPlaceId(place.getId());
        if (!place.isPending() || count < properties.getCorrectionThreshold() || !place.isLocated()) {
            return PlaceOutcome.pending(place, count);
        }

        Zone from = zones.findById(place.getFromZone()).orElseThrow();

        // A resident naming somewhere is quite often naming a zone the seed
        // already has, under the name people actually use for it. That is the
        // field survey happening, not a duplicate.
        Optional<Zone> nearby = nearestWithin(place.getLocation(), properties.getPlaceMergeMetres());
        boolean merged = nearby.isPresent();
        Zone target = nearby.orElseGet(() -> createZone(place, from));

        if (merged && (target.getLandmark() == null || target.getLandmark().isBlank())) {
            // The zone had no name a resident would recognise. Now it has one.
            target.setLandmark(place.getLandmark());
            target.setNeedsFieldNaming(false);
            zones.save(target);
            log.info("zone {} named '{}' by residents", target.getId(), place.getLandmark());
        }

        DrainageEdge edge = null;
        if (!target.getId().equals(from.getId())) {
            edge = edges.findByFromZoneAndToZone(from.getId(), target.getId())
                    .orElseGet(() -> edges.save(ProposedEdges.inferred(from, target, properties)));
        }

        place.promoteTo(target.getId(), at);
        places.save(place);
        log.info("place '{}' promoted to zone {} on {} distinct voices, {}",
                place.getLandmark(), target.getId(), count,
                merged ? "merged into an existing zone" : "created as a new zone");

        return new PlaceOutcome(place, count, true, target, edge, merged);
    }

    /**
     * A place is a claim about where water goes next, not about where the person
     * happens to be. Beyond this range the two have almost certainly been
     * confused, and the resulting edge would carry a travel time too long to act
     * on even if it were true.
     */
    private void requirePlausible(Zone from, Point location) {
        double metres = Geo.metresBetween(from.getLocation(), location);
        if (metres <= properties.getPlaceMergeMetres()) {
            // Close enough that promotion would merge it straight back into the
            // origin, leaving a claim that water flows from a zone to itself.
            throw new IllegalArgumentException(String.format(
                    "that spot is %s itself. Point to where the water goes next, not to where"
                            + " it starts.", from.getId()));
        }
        if (metres > properties.getMaxProposedPlaceMetres()) {
            throw new IllegalArgumentException(String.format(
                    "that spot is %.1f km from %s, too far downstream to be the next place water"
                            + " reaches. Point to the place on the map rather than sending where"
                            + " you are standing.",
                    metres / 1000, from.getId()));
        }
    }

    private Zone createZone(ProposedPlace place, Zone from) {
        Zone created = new Zone(nextZoneId(), from.getCorridor(), place.getLocation(), false);
        // Corridor is inherited from where the water comes from. It is the least
        // invented answer available: nobody has surveyed which corridor this
        // sits on, and the origin is the only evidence there is.
        created.setLandmark(place.getLandmark());
        created.setSource(Zone.RESIDENT);
        return zones.save(created);
    }

    /** Continues the seeded Z01 run rather than starting a second id scheme. */
    private String nextZoneId() {
        int highest = zones.findAllByOrderByIdAsc().stream()
                .map(z -> ZONE_ID.matcher(z.getId()))
                .filter(Matcher::matches)
                .mapToInt(m -> Integer.parseInt(m.group(1)))
                .max()
                .orElse(0);
        return String.format("Z%02d", highest + 1);
    }

    private Optional<Zone> nearestWithin(Point location, double metres) {
        return zones.findAllByOrderByIdAsc().stream()
                .filter(z -> Geo.metresBetween(location, z.getLocation()) <= metres)
                .min(Comparator.comparingDouble(z -> Geo.metresBetween(location, z.getLocation())));
    }

    private static Point pointOrNull(Double lat, Double lng) {
        if (lat == null || lng == null) return null;
        Point point = GEOMETRY.createPoint(new Coordinate(lng, lat));
        point.setSRID(4326);
        return point;
    }

    private static String clean(String raw) {
        return raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
    }
}
