package ng.ekoalert.domain.repo;

import ng.ekoalert.domain.model.AlertDeliveryRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlertDeliveryRepository extends JpaRepository<AlertDeliveryRecord, Long> {

    List<AlertDeliveryRecord> findByAlertId(Long alertId);
}
