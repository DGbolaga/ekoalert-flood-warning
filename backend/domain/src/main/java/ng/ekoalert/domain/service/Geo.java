package ng.ekoalert.domain.service;

import org.locationtech.jts.geom.Point;

/** Great-circle distance. Enough for estimating an edge nobody has timed yet. */
final class Geo {

    private static final double EARTH_RADIUS_M = 6_371_000d;

    private Geo() {
    }

    /** JTS stores longitude in x and latitude in y. */
    static double metresBetween(Point a, Point b) {
        double lat1 = Math.toRadians(a.getY());
        double lat2 = Math.toRadians(b.getY());
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(b.getX() - a.getX());

        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1d, Math.sqrt(h)));
    }
}
