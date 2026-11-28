package net.leaf.predict;

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
    @DisplayName("Classes in net.leaf.predict.. only depend on java.. and self")
    void predictApiClassesOnlyDependOnJavaBaseAndSelf() {
        JavaClasses imported = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("net.leaf.predict");

        assertFalse(imported.isEmpty(), "Expected production classes in net.leaf.predict");

        classes()
                .that().resideInAPackage("net.leaf.predict..")
                .should().onlyDependOnClassesThat()
                .resideInAnyPackage(
                        "java..",
                        "net.leaf.predict.."
                )
                .check(imported);
    }
}
