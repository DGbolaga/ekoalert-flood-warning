package ng.ekoalert.api.web;

import jakarta.validation.Valid;
import ng.ekoalert.domain.model.Subscription;
import ng.ekoalert.domain.repo.SubscriptionRepository;
import ng.ekoalert.domain.repo.ZoneRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Residents asking to hear about a zone. No login: warning people should be easy to opt into. */
@RestController
@RequestMapping("/api/v1/subscriptions")
public class SubscriptionController {

    private final SubscriptionRepository subscriptions;
    private final ZoneRepository zones;

    public SubscriptionController(SubscriptionRepository subscriptions, ZoneRepository zones) {
        this.subscriptions = subscriptions;
        this.zones = zones;
    }

    @PostMapping
    public ResponseEntity<Dtos.SubscriptionResponse> subscribe(
            @Valid @RequestBody Dtos.SubscriptionRequest request) {
        if (!zones.existsById(request.zoneId())) {
            throw new IllegalArgumentException("unknown zone: " + request.zoneId());
        }
        String channel = request.channel() != null ? request.channel() : Subscription.CHANNEL_SSE;

        Subscription subscription = subscriptions
                .findByZoneIdAndChannelAndAddress(request.zoneId(), channel, request.address())
                .orElseGet(() -> subscriptions.save(
                        new Subscription(request.zoneId(), channel, request.address())));

        return ResponseEntity.status(201).body(new Dtos.SubscriptionResponse(
                subscription.getId(), subscription.getZoneId(),
                subscription.getChannel(), subscription.getAddress()));
    }
}
