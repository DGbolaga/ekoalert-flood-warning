package ng.ekoalert.domain.repo;

import ng.ekoalert.domain.model.DrainageEdge;
import ng.ekoalert.engine.Confidence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DrainageEdgeRepository extends JpaRepository<DrainageEdge, Long> {

    List<DrainageEdge> findAllByOrderByIdAsc();

    List<DrainageEdge> findByFromZoneOrderByIdAsc(String fromZone);

    List<DrainageEdge> findByToZoneOrderByIdAsc(String toZone);

    Optional<DrainageEdge> findByFromZoneAndToZone(String fromZone, String toZone);

    boolean existsByFromZoneAndToZone(String fromZone, String toZone);

    long countByConfidence(Confidence confidence);
}
