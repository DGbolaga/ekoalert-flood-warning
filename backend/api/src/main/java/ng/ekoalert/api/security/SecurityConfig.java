package ng.ekoalert.api.security;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Reporters and admins are authenticated. The map, the graph and the live stream
 * are public: a warning nobody can read is not a warning.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;
    private final JsonErrorResponses errors;
    private final CorsConfigurationSource corsConfigurationSource;

    public SecurityConfig(JwtAuthenticationFilter jwtFilter,
                          JsonErrorResponses errors,
                          CorsConfigurationSource corsConfigurationSource) {
        this.jwtFilter = jwtFilter;
        this.errors = errors;
        this.corsConfigurationSource = corsConfigurationSource;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                // No cookies and no sessions, so there is no cross site request
                // forgery surface to protect.
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // A denied request is forwarded to /error, and that
                        // forward re-enters this chain with the security context
                        // already cleared. Without this the container rewrites
                        // every honest 403 into a 401, which tells a client to
                        // log in again when the real answer is that the account
                        // lacks the role.
                        .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll()
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/zones/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/graph").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/alerts/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/subscriptions").permitAll()
                        // Pending places are readable by anyone: a place nobody
                        // can see is a place nobody can corroborate.
                        .requestMatchers(HttpMethod.GET, "/api/v1/places").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/replay").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/reports").hasRole("REPORTER")
                        .requestMatchers("/api/v1/edges/**").hasAnyRole("REPORTER", "ADMIN")
                        .requestMatchers("/api/v1/places/**").hasAnyRole("REPORTER", "ADMIN")
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().authenticated())
                // Anonymous callers on a protected route get 401, not 403. The
                // distinction matters to a client deciding whether to prompt for
                // a login or to say the account lacks the role.
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(errors)
                        .accessDeniedHandler(errors))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
