package ng.ekoalert.domain.repo;

import ng.ekoalert.domain.model.Zone;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ZoneRepository extends JpaRepository<Zone, String> {

    List<Zone> findAllByOrderByIdAsc();

    List<Zone> findByNeedsFieldNamingTrue();
}
