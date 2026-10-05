package io.github.dailystruggle.bstats.api;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** bstats-api-ADR-001: the module depends on the JDK only. */
class BStatsApiArchTest {

    @Test
    @DisplayName("No RTP, platform or third-party dependency")
    void jdkOnly() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("io.github.dailystruggle.bstats.api..")
                .should().dependOnClassesThat().resideOutsideOfPackages(
                        "io.github.dailystruggle.bstats.api..", "java..")
                .because("bstats-api-ADR-001: reusable by any plugin without dragging RTP or a platform API");
        rule.check(new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.github.dailystruggle.bstats.api"));
    }
}
