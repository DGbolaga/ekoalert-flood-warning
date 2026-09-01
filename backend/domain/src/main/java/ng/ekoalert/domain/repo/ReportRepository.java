package ng.ekoalert.domain.repo;

import ng.ekoalert.domain.model.Report;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ReportRepository extends JpaRepository<Report, Long> {

    /**
     * Reports in a zone inside the quorum window that are allowed to count: the
     * reporter must be verified and not suspended. A suspended reporter's
     * reports are stored but never counted, which is why the filter lives in the
     * query and not in the caller.
     */
    @Query("""
            select r from Report r
            where r.zoneId = :zoneId
              and r.observedAt >= :windowStart
              and r.observedAt <= :windowEnd
              and r.reporterId in (
                  select rep.id from Reporter rep
                  where rep.suspended = false and rep.verifiedAt is not null)
            order by r.observedAt desc, r.id desc
            """)
    List<Report> findCountableInWindow(@Param("zoneId") String zoneId,
                                       @Param("windowStart") Instant windowStart,
                                       @Param("windowEnd") Instant windowEnd);

    @Query("select max(r.observedAt) from Report r where r.zoneId = :zoneId")
    Instant findLatestObservedAt(@Param("zoneId") String zoneId);
}
