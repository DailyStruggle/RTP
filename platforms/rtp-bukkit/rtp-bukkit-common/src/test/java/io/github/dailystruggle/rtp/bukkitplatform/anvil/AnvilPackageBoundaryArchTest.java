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
 * Bytecode-level boundary rules for the Spigot-exclusive Anvil read-only pre-filter package.
 *
 * <p>Per ADR-016 and {@code ADR-016} section 2, the pre-filter is confined
 * to {@code io.github.dailystruggle.rtp.bukkitplatform.anvil}. Paper overrides {@code getChunkAt}
 * with its native async API, and Folia extends {@link io.github.dailystruggle.rtp.api.world.RTPWorld}
 * directly - so structural exclusivity is guaranteed by the class hierarchy. These rules
 * lock the invariants in at the bytecode level so a future contributor cannot accidentally
 * reintroduce a coupling that would defeat the hierarchy-based design.</p>
 *
 * <p>The rules scanned here target only {@code rtp-bukkit-common}'s own classpath. The
 * "core/api do not depend on anvil" direction is enforced by a separate rule in
 * {@code RTPArchitectureTest} (rtp-core).</p>
 */
@DisplayName("ADR-016 - Anvil package boundary")
class AnvilPackageBoundaryArchTest {

  /**
   * Production-only classpath scan (excludes test sources; test code is allowed to
   * reach into the anvil package for fixture / reconciliation tests).
   */
  private static final JavaClasses PRODUCTION_CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("io.github.dailystruggle.rtp.bukkitplatform");

  @Test
  @DisplayName("Only classes inside ..bukkitplatform.. may depend on ..bukkitplatform.anvil..")
  void anvilPackageIsSpigotOnly() {
    ArchRule rule =
        classes()
            .that()
            .resideInAPackage("..bukkitplatform.anvil..")
            .should()
            .onlyHaveDependentClassesThat()
            .resideInAnyPackage(
                "..bukkitplatform.anvil..",
                "..bukkitplatform..")
            .because(
                "The Anvil read-only pre-filter is a Spigot-exclusive optimisation "
                    + "(ADR-016). Paper overrides BukkitRTPWorld.getChunkAt with its native "
                    + "async path and Folia extends RTPWorld directly; neither should reach "
                    + "into io.github.dailystruggle.rtp.bukkitplatform.anvil at the bytecode level.");

    rule.check(PRODUCTION_CLASSES);
  }

  @Test
  @DisplayName("Anvil package does not depend on org.bukkit.Chunk (reader must operate on unloaded chunks)")
  void anvilDoesNotDependOnBukkitChunk() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("..bukkitplatform.anvil..")
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("org.bukkit.Chunk")
            .because(
                "The Anvil pre-filter deliberately operates on region-file bytes for "
                    + "*unloaded* chunks. A direct dependency on org.bukkit.Chunk would "
                    + "indicate a regression to the live-load path and defeat the whole "
                    + "off-thread design (ADR-016 section 3 Verdict contract).");

    rule.check(PRODUCTION_CLASSES);
  }
}
