package ng.ekoalert.api.sse;

import ng.ekoalert.domain.model.Alert;
import ng.ekoalert.domain.service.AlertPublisher;
import ng.ekoalert.engine.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Pushes alerts to whoever has the map open.
 *
 * <p>The domain publishes through {@link AlertPublisher} and never learns that
 * the transport is server-sent events. A subscriber that has gone away is
 * dropped quietly; a browser closing a tab is not an incident.
 */
@Component
public class SseAlertPublisher implements AlertPublisher {

    private static final Logger log = LoggerFactory.getLogger(SseAlertPublisher.class);

    /** An open stream and the zones it asked about. An empty filter means all of them. */
    private record Listener(SseEmitter emitter, Set<String> zoneFilter) {

        boolean wants(String zoneId) {
            return zoneFilter.isEmpty() || zoneFilter.contains(zoneId);
        }
    }

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    public SseEmitter open(Set<String> zoneFilter, long timeoutMillis) {
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        Listener listener = new Listener(emitter, Set.copyOf(zoneFilter));
        listeners.add(listener);

        emitter.onCompletion(() -> listeners.remove(listener));
        emitter.onTimeout(() -> listeners.remove(listener));
        emitter.onError(error -> listeners.remove(listener));

        try {
            // Opens the stream immediately so a client knows it is connected
            // rather than waiting for the first flood to find out.
            emitter.send(SseEmitter.event().name("connected")
                    .data(Map.of("zones", zoneFilter, "at", Instant.now().toString())));
        } catch (IOException e) {
            listeners.remove(listener);
            emitter.completeWithError(e);
        }
        return emitter;
    }

    @Override
    public void alertFired(Alert alert) {
        broadcast(alert.getTargetZone(), "alert", Map.of(
                "id", alert.getId(),
                "originZone", alert.getOriginZone(),
                "targetZone", alert.getTargetZone(),
                "level", alert.getLevel().name(),
                "etaMinutes", alert.getEtaMinutes(),
                "hops", alert.getHops(),
                "firedAt", alert.getFiredAt().toString()));
    }

    @Override
    public void allClear(String originZone, String targetZone, Instant at) {
        broadcast(targetZone, "all-clear", Map.of(
                "originZone", originZone,
                "targetZone", targetZone,
                "at", at.toString()));
    }

    @Override
    public void zoneStatusChanged(String zoneId, Severity level, Instant at) {
        broadcast(zoneId, "zone-status", level == null
                ? Map.of("zoneId", zoneId, "level", "CLEAR", "at", at.toString())
                : Map.of("zoneId", zoneId, "level", level.name(), "at", at.toString()));
    }

    private void broadcast(String zoneId, String eventName, Object payload) {
        for (Listener listener : listeners) {
            if (!listener.wants(zoneId)) {
                continue;
            }
            try {
                listener.emitter().send(SseEmitter.event().name(eventName).data(payload));
            } catch (IOException | IllegalStateException e) {
                listeners.remove(listener);
                log.debug("dropped a closed stream: {}", e.getMessage());
            }
        }
    }

    /**
     * Keeps idle streams alive through whatever proxy sits in front of us.
     *
     * <p>The normal state of this system is silence: a full map that sends
     * almost nothing until an edge is confirmed. A stream that carries no bytes
     * for minutes at a time is indistinguishable from a dead one to a reverse
     * proxy, and most cut it well inside our own thirty minute timeout. The
     * browser reconnects, so nothing is lost, but the map spends its time
     * flapping between live and disconnected and tells the reader the system is
     * broken when it is merely quiet.
     *
     * <p>A comment rather than an event, so no client code has to know.
     */
    @Scheduled(fixedDelayString = "${ekoalert.sse.keepalive-millis:20000}")
    void keepAlive() {
        for (Listener listener : listeners) {
            try {
                listener.emitter().send(SseEmitter.event().comment("keepalive"));
            } catch (IOException | IllegalStateException e) {
                listeners.remove(listener);
                log.debug("dropped a closed stream on keepalive: {}", e.getMessage());
            }
        }
    }

    /** How many streams are open. Used by tests and by the admin view. */
    public int listenerCount() {
        return listeners.size();
    }
}
