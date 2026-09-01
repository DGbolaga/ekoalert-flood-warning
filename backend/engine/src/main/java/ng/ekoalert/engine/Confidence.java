package ng.ekoalert.engine;

/**
 * How much the graph believes an edge.
 *
 * <p>Every edge is seeded {@link #INFERRED} so the map is complete on day one.
 * Inferred edges are drawn and traversed, but no path containing one is
 * alertable. An edge becomes {@link #CONFIRMED} when residents affirm it past a
 * threshold or observed reports show the timing holds, and {@link #REJECTED} the
 * same way.
 */
public enum Confidence {

    /** Seeded by geometric inference. Traversed for display, never alertable. */
    INFERRED,

    /** Affirmed by residents or by observed timing. The only alertable state. */
    CONFIRMED,

    /** Contradicted by residents or by observed timing. Never traversed. */
    REJECTED
}
