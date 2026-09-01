package ng.ekoalert.engine;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The engine is pure Java. If it ever imports Spring, JPA, or anything that
 * touches a database or the filesystem, that is a build failure, not a style
 * problem. This test is the enforcement.
 */
class EnginePurityTest {

    private static JavaClasses engineClasses;

    @BeforeAll
    static void importEngine() {
        engineClasses = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("ng.ekoalert.engine");
    }

    @Test
    @DisplayName("engine does not depend on Spring")
    void noSpring() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage("org.springframework..")
                .because("the engine must compile and test with Spring absent")
                .check(engineClasses);
    }

    @Test
    @DisplayName("engine does not depend on JPA or Jakarta persistence")
    void noJpa() {
        noClasses().should().dependOnClassesThat()
                .resideInAnyPackage("jakarta.persistence..", "javax.persistence..", "jakarta.transaction..")
                .because("the engine must compile and test with the database absent")
                .check(engineClasses);
    }

    @Test
    @DisplayName("engine performs no I/O")
    void noIo() {
        noClasses().should().dependOnClassesThat()
                .resideInAnyPackage("java.sql..", "javax.sql..", "java.net..", "java.io..", "java.nio.file..")
                .because("the engine performs no I/O; it is pure functions over records")
                .check(engineClasses);
    }

    @Test
    @DisplayName("engine does not depend on Hibernate, Jackson, or a logging framework")
    void noFrameworks() {
        noClasses().should().dependOnClassesThat()
                .resideInAnyPackage("org.hibernate..", "com.fasterxml.jackson..", "org.slf4j..", "ch.qos.logback..")
                .because("the engine is reusable for any city and carries no framework baggage")
                .check(engineClasses);
    }
}
