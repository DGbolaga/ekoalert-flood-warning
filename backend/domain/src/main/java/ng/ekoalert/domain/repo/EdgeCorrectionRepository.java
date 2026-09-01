package ng.ekoalert.domain.repo;

import ng.ekoalert.domain.model.EdgeCorrection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface EdgeCorrectionRepository extends JpaRepository<EdgeCorrection, Long> {

    List<EdgeCorrection> findByEdgeIdOrderByIdAsc(Long edgeId);

    /**
     * How many distinct reporters took this action on this edge. Distinct is the
     * point: the threshold is a count of people, not a count of taps.
     */
    @Query("""
            select count(distinct c.reporterId) from EdgeCorrection c
            where c.edgeId = :edgeId and c.action = :action and c.reporterId is not null
            """)
    long countDistinctReporters(@Param("edgeId") Long edgeId, @Param("action") String action);

    @Query("""
            select count(distinct c.reporterId) from EdgeCorrection c
            where c.fromZone = :fromZone and c.toZone = :toZone
              and c.action = :action and c.reporterId is not null
            """)
    long countDistinctReportersForPair(@Param("fromZone") String fromZone,
                                       @Param("toZone") String toZone,
                                       @Param("action") String action);
}
