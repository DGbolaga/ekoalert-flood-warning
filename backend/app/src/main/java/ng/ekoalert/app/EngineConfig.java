package ng.ekoalert.app;

import ng.ekoalert.engine.BestFirstPropagationEngine;
import ng.ekoalert.engine.PropagationEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The engine knows nothing about Spring, so Spring is told about it here rather
 * than the other way round. This class is the only place the two meet.
 */
@Configuration
public class EngineConfig {

    @Bean
    public PropagationEngine propagationEngine() {
        return new BestFirstPropagationEngine();
    }
}
