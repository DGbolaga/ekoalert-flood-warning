package ng.ekoalert.domain.repo;

import ng.ekoalert.domain.model.SystemFlag;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SystemFlagRepository extends JpaRepository<SystemFlag, String> {
}
