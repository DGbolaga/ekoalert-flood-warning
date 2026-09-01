package ng.ekoalert.domain.repo;

import ng.ekoalert.domain.model.Alert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface AlertRepository extends JpaRepository<Alert, Long> {

    List<Alert> findByOriginZoneAndFiredAtGreaterThanEqualOrderByIdAsc(String originZone, Instant firedAt);

    /** Alerts that actually went out, which is who the all-clear owes a message to. */
    List<Alert> findByOriginZoneAndSuppressedByIsNullOrderByIdAsc(String originZone);

    List<Alert> findTop100ByOrderByFiredAtDescIdDesc();
}
