package ng.ekoalert.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes the same error body for 401 and 403 that the controllers write for 400.
 *
 * <p>Left to itself the container answers these two with Spring Boot's default
 * error shape, which means a client has to understand two error formats to
 * handle one API. One shape everywhere is worth the twenty lines.
 */
@Component
public class JsonErrorResponses implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper json;

    public JsonErrorResponses(ObjectMapper json) {
        this.json = json;
    }

    /** Anonymous caller on a protected route. The client should log in. */
    @Override
    public void commence(jakarta.servlet.http.HttpServletRequest request,
                         HttpServletResponse response,
                         org.springframework.security.core.AuthenticationException authException)
            throws IOException {
        write(response, HttpServletResponse.SC_UNAUTHORIZED,
                "unauthorized", "authentication required");
    }

    /** Authenticated caller without the role. Logging in again will not help. */
    @Override
    public void handle(jakarta.servlet.http.HttpServletRequest request,
                       HttpServletResponse response,
                       org.springframework.security.access.AccessDeniedException accessDeniedException)
            throws IOException {
        write(response, HttpServletResponse.SC_FORBIDDEN,
                "forbidden", "this account does not have the role for that");
    }

    private void write(HttpServletResponse response, int status, String error, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("message", message);
        body.put("at", Instant.now().toString());
        json.writeValue(response.getWriter(), body);
    }
}
