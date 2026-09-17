package io.github.dailystruggle.rtp.bukkitplatform.anvil;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Bytecode-level boundary rules for Anvil usage in Bukkit platform adapter.
 *
 * <p>Per ADR-016 and ADR-077, Anvil prefiltering is unified under {@code io.github.dailystruggle.rtp.anvil}.
 * All Bukkit platform classes must interact with Anvil through {@code anvil-api} and {@code RTPServerAccessor}.</p>
 */
@DisplayName("ADR-016 - Anvil package boundary")
class AnvilPackageBoundaryArchTest {

  private static final JavaClasses PRODUCTION_CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("io.github.dailystruggle.rtp.bukkitplatform");

  @Test
  @DisplayName("Bukkit platform shall not maintain internal anvil parser implementations")
  void noInternalAnvilPackageInBukkitPlatform() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("..bukkitplatform..")
            .should()
            .resideInAPackage("..bukkitplatform.anvil..")
            .because(
                "ADR-016 / ADR-077: Anvil parser classes are consolidated in the platform-neutral "
                    + "anvil-api module; rtp-bukkit shall not duplicate them.");

    rule.check(PRODUCTION_CLASSES);
  }
}
