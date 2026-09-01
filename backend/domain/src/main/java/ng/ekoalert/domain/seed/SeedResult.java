package ng.ekoalert.domain.seed;

import java.util.List;

/**
 * What a seed run did and what the database now holds.
 *
 * @param mergedDuplicates zone ids folded into an earlier zone because they sit
 *                         on effectively the same coordinate
 * @param skippedEdges     human readable reasons an edge in the file produced no row
 */
public record SeedResult(int zonesInserted,
                         int zonesAlreadyPresent,
                         List<String> mergedDuplicates,
                         int edgesInserted,
                         int edgesAlreadyPresent,
                         List<String> skippedEdges,
                         long totalZones,
                         long totalEdges,
                         long confirmedEdges) {

    /** The line the loader prints, and the one the definition of done checks. */
    public String summary() {
        return "zones=%d edges=%d confirmed=%d (inserted %d zones, %d edges; merged %d duplicates)"
                .formatted(totalZones, totalEdges, confirmedEdges,
                        zonesInserted, edgesInserted, mergedDuplicates.size());
    }
}
