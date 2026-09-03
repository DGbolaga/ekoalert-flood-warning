package ng.ekoalert.domain.repo;

import ng.ekoalert.domain.model.ProposedPlace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProposedPlaceRepository extends JpaRepository<ProposedPlace, Long> {

    List<ProposedPlace> findByStatusOrderByIdAsc(String status);

    List<ProposedPlace> findByFromZoneAndStatusOrderByIdAsc(String fromZone, String status);
}
