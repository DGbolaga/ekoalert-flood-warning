package ng.ekoalert.app;

import ng.ekoalert.domain.service.AlertingProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * A modular monolith, not microservices. One deployable, four modules, and the
 * dependencies point one way: engine, then domain, then api, then here.
 */
@SpringBootApplication(
        scanBasePackages = "ng.ekoalert",
        // Authentication goes through AuthController against app_user. Left on,
        // this auto-configuration invents a default user and prints a generated
        // password at every startup, which is a credential nobody asked for in a
        // system that warns real people.
        exclude = UserDetailsServiceAutoConfiguration.class)
@EnableJpaRepositories(basePackages = "ng.ekoalert.domain.repo")
@EntityScan(basePackages = "ng.ekoalert.domain.model")
@EnableConfigurationProperties(AlertingProperties.class)
@EnableScheduling
public class EkoAlertApplication {

    public static void main(String[] args) {
        SpringApplication.run(EkoAlertApplication.class, args);
    }
}
