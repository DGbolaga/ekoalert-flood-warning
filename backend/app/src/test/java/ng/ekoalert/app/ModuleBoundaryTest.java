package ng.ekoalert.app;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Dependencies point one way only: engine, then domain, then api, then app.
 *
 * <p>This runs from the app module, where the whole assembled classpath is
 * visible, so it catches a violation the engine module cannot see on its own.
 * The engine module has its own purity test; this one guards the layering.
 */
class ModuleBoundaryTest {

    private static JavaClasses all;

    @BeforeAll
    static void importAll() {
        all = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("ng.ekoalert");
    }

    @Test
    @DisplayName("the modules form a one way stack")
    void layering() {
        layeredArchitecture().consideringOnlyDependenciesInLayers()
                .layer("engine").definedBy("ng.ekoalert.engine..")
                .layer("domain").definedBy("ng.ekoalert.domain..")
                .layer("api").definedBy("ng.ekoalert.api..")
                .layer("app").definedBy("ng.ekoalert.app..")
                .whereLayer("app").mayNotBeAccessedByAnyLayer()
                .whereLayer("api").mayOnlyBeAccessedByLayers("app")
                .whereLayer("domain").mayOnlyBeAccessedByLayers("api", "app")
                .whereLayer("engine").mayOnlyBeAccessedByLayers("domain", "api", "app")
                .check(all);
    }

    @Test
    @DisplayName("the engine stays free of Spring and JPA on the assembled classpath too")
    void engineStaysPure() {
        noClasses().that().resideInAPackage("ng.ekoalert.engine..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework..", "jakarta.persistence..", "org.hibernate..")
                .because("the engine must compile and test with Spring and the database absent")
                .check(all);
    }
}
