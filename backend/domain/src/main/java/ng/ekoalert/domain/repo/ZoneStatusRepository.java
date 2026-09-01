package ng.ekoalert.domain.repo;

import ng.ekoalert.domain.model.ZoneStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ZoneStatusRepository extends JpaRepository<ZoneStatus, String> {

    @Query("select s from ZoneStatus s where s.escalatedAt is not null and s.clearedAt is null")
    List<ZoneStatus> findActive();
}
