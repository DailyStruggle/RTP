package io.github.dailystruggle.rtp.common.predict;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.junit.jupiter.api.Assertions.assertFalse;

@DisplayName("REQ-PRED-NF-002: Predict-API Pure Java Boundary Enforcement")
class PredictApiBoundaryArchTest {

    @Test
    @DisplayName("Classes in io.github.dailystruggle.rtp.common.predict.. only depend on java.. and self")
    void predictApiClassesOnlyDependOnJavaBaseAndSelf() {
        JavaClasses imported = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.github.dailystruggle.rtp.common.predict");

        assertFalse(imported.isEmpty(), "Expected production classes in io.github.dailystruggle.rtp.common.predict");

        classes()
                .that().resideInAPackage("io.github.dailystruggle.rtp.common.predict..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage(
                        "java..",
                        "io.github.dailystruggle.rtp.common.predict.."
                )
                .check(imported);
    }
}
