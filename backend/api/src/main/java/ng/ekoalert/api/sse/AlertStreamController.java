package ng.ekoalert.api.sse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Set;

/** Live alerts for the map. Public, like the map itself. */
@RestController
@RequestMapping("/api/v1/alerts")
public class AlertStreamController {

    private final SseAlertPublisher publisher;
    private final long timeoutMillis;

    public AlertStreamController(SseAlertPublisher publisher,
                                 @Value("${ekoalert.sse.timeout-millis:1800000}") long timeoutMillis) {
        this.publisher = publisher;
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * @param zones optional filter. Omit it to watch the whole city, which is
     *              what the operations map does.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(required = false) List<String> zones) {
        return publisher.open(zones == null ? Set.of() : Set.copyOf(zones), timeoutMillis);
    }
}
