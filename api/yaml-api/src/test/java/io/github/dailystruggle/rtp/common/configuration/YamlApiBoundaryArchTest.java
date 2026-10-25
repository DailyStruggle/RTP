package io.github.dailystruggle.rtp.common.configuration;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Architectural boundary verification for {@code yaml-api}: classes in {@code yaml-api} are
 * layered on {@code units-api} and {@code server-api} only and must never import platform packages
 * ({@code org.bukkit.*}, {@code net.minecraft.*}) or {@code rtp-core} packages/types
 * ({@code tasks..}, {@code database..}, {@code RTP}).
 */
@DisplayName("yaml-api: Module Boundary Architecture")
class YamlApiBoundaryArchTest {
  private static JavaClasses classes;

  @BeforeAll
  static void importClasses() {
    classes =
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(
                "io.github.dailystruggle.rtp.common.configuration",
                "io.github.dailystruggle.rtp.common.factory",
                "io.github.dailystruggle.rtp.common.search");
  }

  @Test
  @DisplayName("yaml-api classes never import platform packages or rtp-core tasks/database/RTP")
  void yamlApiClassesDoNotDependOnPlatformOrRtpCorePackages() {
    assertFalse(classes.isEmpty(), "Expected production classes in yaml-api packages");

    noClasses()
        .that()
        .resideInAnyPackage(
            "io.github.dailystruggle.rtp.common.configuration..",
            "io.github.dailystruggle.rtp.common.factory..",
            "io.github.dailystruggle.rtp.common.search..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "org.bukkit..",
            "net.minecraft..",
            "io.github.dailystruggle.rtp.common.tasks..",
            "io.github.dailystruggle.rtp.common.database..")
        .orShould()
        .dependOnClassesThat()
        .haveFullyQualifiedName("io.github.dailystruggle.rtp.common.RTP")
        .check(classes);
  }
}
