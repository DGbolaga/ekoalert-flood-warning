package ng.ekoalert.app;

import ng.ekoalert.domain.seed.SeedLoader;
import ng.ekoalert.domain.seed.SeedResult;
import ng.ekoalert.engine.Confidence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seed loader against the real file and a real database.
 *
 * <p>The counts here are the ones in the definition of done: twenty zones,
 * seventeen edges, nothing confirmed.
 */
class SeedLoaderIT extends IntegrationTestBase {

    @Autowired SeedLoader loader;

    @Test
    @DisplayName("a fresh database loads to 20 zones, 17 edges and 0 confirmed")
    void freshLoad() {
        SeedResult result = loader.load();

        assertThat(result.totalZones()).isEqualTo(20);
        assertThat(result.totalEdges()).isEqualTo(17);
        assertThat(result.confirmedEdges()).isZero();
        assertThat(result.zonesInserted()).isEqualTo(20);
        assertThat(result.edgesInserted()).isEqualTo(17);
    }

    @Test
    @DisplayName("every seeded edge is inferred, without exception")
    void everySeededEdgeIsInferred() {
        loader.load();

        assertThat(edges.countByConfidence(Confidence.INFERRED)).isEqualTo(17);
        assertThat(edges.countByConfidence(Confidence.CONFIRMED)).isZero();
        assertThat(edges.countByConfidence(Confidence.REJECTED)).isZero();
        assertThat(edges.findAll()).noneMatch(edge -> edge.isBlocked());
    }

    @Test
    @DisplayName("running the loader twice changes nothing")
    void idempotent() {
        loader.load();
        SeedResult second = loader.load();

        assertThat(second.zonesInserted()).isZero();
        assertThat(second.edgesInserted()).isZero();
        assertThat(second.zonesAlreadyPresent()).isEqualTo(20);
        assertThat(second.edgesAlreadyPresent()).isEqualTo(17);
        assertThat(second.totalZones()).isEqualTo(20);
        assertThat(second.totalEdges()).isEqualTo(17);
    }

    @Test
    @DisplayName("the two rows six metres apart are treated as one place, and the chain survives it")
    void duplicateCoordinatesAreMerged() {
        SeedResult result = loader.load();

        assertThat(result.mergedDuplicates()).hasSize(1);
        assertThat(result.mergedDuplicates().get(0)).contains("Z14").contains("Z09");
        assertThat(zones.existsById("Z14")).isFalse();
        assertThat(zones.existsById("Z09")).isTrue();

        // Z13 to Z09 to Z10 has to still be walkable once Z14 folds into Z09.
        assertThat(edges.findByFromZoneAndToZone("Z13", "Z09")).isPresent();
        assertThat(edges.findByFromZoneAndToZone("Z09", "Z10")).isPresent();
        assertThat(edges.findByFromZoneAndToZone("Z09", "Z09")).isEmpty();
    }

    @Test
    @DisplayName("corridor termini get a zone and no outbound edge")
    void terminiHaveNoOutboundEdge() {
        loader.load();

        assertThat(zones.existsById("Z08")).isTrue();
        assertThat(zones.existsById("Z11")).isTrue();
        assertThat(zones.existsById("Z21")).isTrue();
        assertThat(edges.findByFromZoneOrderByIdAsc("Z08")).isEmpty();
        assertThat(edges.findByFromZoneOrderByIdAsc("Z11")).isEmpty();
        assertThat(edges.findByFromZoneOrderByIdAsc("Z21")).isEmpty();
    }

    @Test
    @DisplayName("names and landmarks stay blank, because those come from a person")
    void namesAreNotInferred() {
        loader.load();

        assertThat(zones.findAll()).allSatisfy(zone -> {
            assertThat(zone.getName()).isNull();
            assertThat(zone.getLandmark()).isNull();
        });
    }

    @Test
    @DisplayName("the four zones far from any named place are flagged for a field visit")
    void fieldNamingFlagSurvivesTheLoad() {
        loader.load();

        assertThat(zones.findByNeedsFieldNamingTrue())
                .extracting(zone -> zone.getId())
                .containsExactlyInAnyOrder("Z05", "Z06", "Z07", "Z08");
    }
}
