package io.github.dailystruggle.rtp.anvil;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Bytecode-level boundary rules for the platform-neutral {@code rtp-anvil} module.
 *
 * <p>Per ADR-016, this module decodes vanilla {@code r.X.Z.mca} region files without any
 * RTP-internal or platform-specific dependency. The original ADR-016
 * "Spigot-exclusive" boundary now lives in the platform adapter (see {@code rtp-spigot-common}
 * Architecture rules); the rules here lock the new direction in at the bytecode level so a
 * future contributor cannot accidentally drag a Bukkit type, an RTP-core type, or a
 * Fabric/Paper-namespaced type back into the shared decoder.</p>
 */
@DisplayName("ADR-016 § rtp-anvil platform-neutrality boundary")
class AnvilPackageBoundaryArchTest {

  /**
   * Production-only classpath scan (excludes test sources; tests may use whatever they need).
   */
  private static final JavaClasses PRODUCTION_CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("io.github.dailystruggle.rtp.anvil");

  @Test
  @DisplayName("rtp-anvil shall not depend on any platform package (Bukkit / Paper / Folia / Fabric / Mojang)")
  void anvilDoesNotDependOnAnyPlatform() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("io.github.dailystruggle.rtp.anvil..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "org.bukkit..",
                "io.papermc..",
                "net.minecraft..",
                "net.fabricmc..")
            .because(
                "ADR-016 §4: rtp-anvil is platform-neutral and must remain consumable "
                    + "by any platform adapter (Spigot, Paper, Folia, Fabric) without "
                    + "dragging another platform's API onto the classpath.");

    rule.check(PRODUCTION_CLASSES);
  }

  @Test
  @DisplayName("rtp-anvil shall not depend on any RTP-internal module (rtp-api / rtp-core / commands-api / effects-api / platform adapters)")
  void anvilDoesNotDependOnAnyRtpInternal() {
    ArchRule rule =
        noClasses()
            .that()
            .resideInAPackage("io.github.dailystruggle.rtp.anvil..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "io.github.dailystruggle.rtp.api..",
                "io.github.dailystruggle.rtp.common..",
                "io.github.dailystruggle.rtp.bukkit..",
                "io.github.dailystruggle.rtp.spigot..",
                "io.github.dailystruggle.rtp.paper..",
                "io.github.dailystruggle.rtp.folia..",
                "io.github.dailystruggle.rtp.fabric..",
                "io.github.dailystruggle.commandsapi..",
                "io.github.dailystruggle.effectsapi..")
            .because(
                "ADR-016 §4: rtp-anvil sits below every other RTP module in the dependency "
                    + "graph; reaching upward into rtp-api / rtp-core / a platform adapter "
                    + "would invert that and create a module-cycle risk.");

    rule.check(PRODUCTION_CLASSES);
  }
}
