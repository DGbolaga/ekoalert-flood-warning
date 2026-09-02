package ng.ekoalert.domain.repo;

import ng.ekoalert.domain.model.ProposedPlaceVoice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProposedPlaceVoiceRepository extends JpaRepository<ProposedPlaceVoice, Long> {

    Optional<ProposedPlaceVoice> findByPlaceIdAndReporterId(Long placeId, Long reporterId);

    long countByPlaceId(Long placeId);

    List<ProposedPlaceVoice> findByPlaceId(Long placeId);
}
