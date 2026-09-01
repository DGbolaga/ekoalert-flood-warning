/**
 * The EkoAlert propagation engine.
 *
 * <p>Pure Java 21. No Spring, no JPA, no database, no I/O. The engine takes an
 * immutable snapshot of a drainage graph and an origin zone, and returns the
 * zones water is expected to reach. It is unit-testable in isolation and
 * reusable for any city given a graph.
 *
 * <p>An edge from A to B is not a hydrological claim that a channel connects
 * them. It is an observational claim that water reported in A tends to appear
 * in B roughly N minutes later. The engine treats it as exactly that.
 */
package ng.ekoalert.engine;
