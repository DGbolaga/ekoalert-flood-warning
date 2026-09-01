package ng.ekoalert.domain.seed;

import ng.ekoalert.domain.model.DrainageEdge;
import ng.ekoalert.domain.model.Zone;
import ng.ekoalert.domain.repo.DrainageEdgeRepository;
import ng.ekoalert.domain.repo.ZoneRepository;
import ng.ekoalert.engine.Confidence;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads ekoalert_zones.csv into zone and edge.
 *
 * <p>Idempotent: running it twice inserts nothing the second time. Every seeded
 * edge is {@code inferred}, without exception. The map is complete on day one
 * and silent on day one, and that is the design, not a gap.
 *
 * <p>Three columns in the file are deliberately ignored. {@code zone_name} and
 * {@code landmark} come from a field survey that has not happened; populating
 * them by inference is exactly the mistake the provenance rules exist to
 * prevent. {@code confidence} in the file is not the edge confidence enum: the
 * corridor terminus rows carry {@code unknown}, which is a statement about a
 * junction nobody has inferred rather than a value this schema has.
 */
@Service
public class SeedLoader {

    private static final Logger log = LoggerFactory.getLogger(SeedLoader.class);

    private static final GeometryFactory GEOMETRY =
            new GeometryFactory(new PrecisionModel(), 4326);

    /**
     * Two rows closer together than this are the same place recorded twice. The
     * file has one such pair, six metres apart, which is inside the noise of the
     * OpenStreetMap geometry it came from.
     */
    private static final double MIN_ZONE_SEPARATION_METRES = 25d;

    private static final double EARTH_RADIUS_M = 6_371_000d;

    private final ZoneRepository zones;
    private final DrainageEdgeRepository edges;
    private final ResourceLoader resources;
    private final String seedLocation;

    public SeedLoader(ZoneRepository zones,
                      DrainageEdgeRepository edges,
                      ResourceLoader resources,
                      @Value("${ekoalert.seed.csv:classpath:seed/ekoalert_zones.csv}") String seedLocation) {
        this.zones = zones;
        this.edges = edges;
        this.resources = resources;
        this.seedLocation = seedLocation;
    }

    @Transactional
    public SeedResult load() {
        try (InputStream in = resources.getResource(seedLocation).getInputStream()) {
            return load(CsvReader.read(in));
        } catch (IOException e) {
            throw new UncheckedIOException("could not read seed file " + seedLocation, e);
        }
    }

    @Transactional
    public SeedResult load(List<Map<String, String>> rows) {
        Map<String, String> canonicalId = new LinkedHashMap<>();
        Map<String, double[]> accepted = new LinkedHashMap<>();
        List<String> merged = new ArrayList<>();

        int zonesInserted = 0;
        int zonesAlreadyPresent = 0;

        for (Map<String, String> row : rows) {
            String id = row.get("zone_id");
            double lat = Double.parseDouble(row.get("lat"));
            double lng = Double.parseDouble(row.get("lng"));

            String duplicateOf = findNearby(accepted, lat, lng);
            if (duplicateOf != null) {
                canonicalId.put(id, duplicateOf);
                merged.add(id + " merged into " + duplicateOf);
                log.warn("zone {} sits within {}m of {}; treating it as the same place",
                        id, (int) MIN_ZONE_SEPARATION_METRES, duplicateOf);
                continue;
            }

            canonicalId.put(id, id);
            accepted.put(id, new double[]{lat, lng});

            if (zones.existsById(id)) {
                zonesAlreadyPresent++;
                continue;
            }
            zones.save(new Zone(id, row.get("corridor"), point(lat, lng), yes(row.get("needs_field_naming"))));
            zonesInserted++;
        }

        int edgesInserted = 0;
        int edgesAlreadyPresent = 0;
        List<String> skipped = new ArrayList<>();

        for (Map<String, String> row : rows) {
            String rawFrom = row.get("zone_id");
            String rawTo = row.get("drains_into");
            if (rawTo == null || rawTo.isBlank()) {
                // A corridor terminus. The junction to the next corridor is
                // deliberately not inferred, because those are the edges
                // inference is worst at.
                continue;
            }

            String from = canonicalId.get(rawFrom);
            String to = canonicalId.get(rawTo);
            if (to == null) {
                skipped.add(rawFrom + " to " + rawTo + ": target zone is not in the file");
                continue;
            }
            if (from.equals(to)) {
                skipped.add(rawFrom + " to " + rawTo + ": both sides collapsed onto " + from + " once duplicates merged");
                continue;
            }
            if (edges.existsByFromZoneAndToZone(from, to)) {
                edgesAlreadyPresent++;
                continue;
            }

            edges.save(new DrainageEdge(from, to,
                    parseInt(row.get("travel_minutes")),
                    parseNullableInt(row.get("distance_m")),
                    row.get("inference_basis")));
            edgesInserted++;
        }

        SeedResult result = new SeedResult(
                zonesInserted, zonesAlreadyPresent, List.copyOf(merged),
                edgesInserted, edgesAlreadyPresent, List.copyOf(skipped),
                zones.count(), edges.count(), edges.countByConfidence(Confidence.CONFIRMED));

        log.info("seed loaded: {}", result.summary());
        result.skippedEdges().forEach(reason -> log.info("seed skipped edge: {}", reason));
        return result;
    }

    private static String findNearby(Map<String, double[]> accepted, double lat, double lng) {
        for (Map.Entry<String, double[]> entry : accepted.entrySet()) {
            if (metresBetween(lat, lng, entry.getValue()[0], entry.getValue()[1]) < MIN_ZONE_SEPARATION_METRES) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static double metresBetween(double lat1, double lng1, double lat2, double lng2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dPhi = phi2 - phi1;
        double dLambda = Math.toRadians(lng2 - lng1);
        double h = Math.sin(dPhi / 2) * Math.sin(dPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dLambda / 2) * Math.sin(dLambda / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1d, Math.sqrt(h)));
    }

    private static Point point(double lat, double lng) {
        Point point = GEOMETRY.createPoint(new Coordinate(lng, lat));
        point.setSRID(4326);
        return point;
    }

    private static boolean yes(String value) {
        return value != null && value.equalsIgnoreCase("yes");
    }

    private static int parseInt(String value) {
        return value == null || value.isBlank() ? 0 : Integer.parseInt(value);
    }

    private static Integer parseNullableInt(String value) {
        return value == null || value.isBlank() ? null : Integer.valueOf(value);
    }
}
