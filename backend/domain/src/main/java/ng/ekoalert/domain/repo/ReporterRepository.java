package ng.ekoalert.domain.repo;

import ng.ekoalert.domain.model.Reporter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReporterRepository extends JpaRepository<Reporter, Long> {

    Optional<Reporter> findByPhone(String phone);
}
