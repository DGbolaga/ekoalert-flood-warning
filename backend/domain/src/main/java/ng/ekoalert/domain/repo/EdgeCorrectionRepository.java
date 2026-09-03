package ng.ekoalert.domain.repo;

import ng.ekoalert.domain.model.EdgeCorrection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface EdgeCorrectionRepository extends JpaRepository<EdgeCorrection, Long> {

    List<EdgeCorrection> findByEdgeIdOrderByIdAsc(Long edgeId);

    /**
     * How many distinct reporters currently take this position on this edge.
     *
     * <p>Two rules are doing work here. Distinct, because the threshold counts
     * people rather than taps. And <em>currently</em>: only a reporter's most
     * recent correction on the edge counts, so somebody who confirms and then
     * rejects is one rejection, not one of each. Every tap stays in the table,
     * because the log is the evidence trail; it is the tally that reads only the
     * latest stance, not the history that forgets.
     */
    @Query("""
            select count(distinct c.reporterId) from EdgeCorrection c
            where c.edgeId = :edgeId and c.action = :action and c.reporterId is not null
              and c.id = (select max(c2.id) from EdgeCorrection c2
                          where c2.edgeId = c.edgeId and c2.reporterId = c.reporterId)
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
