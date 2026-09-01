package ng.ekoalert.domain.repo;

import ng.ekoalert.domain.model.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    List<Subscription> findByZoneId(String zoneId);

    Optional<Subscription> findByZoneIdAndChannelAndAddress(String zoneId, String channel, String address);
}
