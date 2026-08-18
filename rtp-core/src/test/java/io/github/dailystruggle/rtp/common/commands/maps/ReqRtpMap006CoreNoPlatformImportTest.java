package io.github.dailystruggle.rtp.common.commands.maps;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-RTP-MAP-006: verifies {@code rtp.common.commands.maps} contains no platform imports
 * (Bukkit, Paper, Minecraft, Fabric) and no blocking {@link java.util.concurrent.CompletableFuture} calls.
 */
@DisplayName("REQ-RTP-MAP-006 - rtp-core commands.maps is platform-neutral and non-blocking")
class ReqRtpMap006CoreNoPlatformImportTest {

  private static final JavaClasses PRODUCTION_CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("io.github.dailystruggle.rtp.common.commands.maps");

  @Test
  @DisplayName("commands.maps shall not import org.bukkit / net.minecraft / paper / fabric")
  void noPlatformImports() {
    ArchRule rule = noClasses()
        .that().resideInAPackage("io.github.dailystruggle.rtp.common.commands.maps..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.bukkit..",
            "io.papermc..",
            "net.minecraft..",
            "net.fabricmc..")
        .because("REQ-RTP-MAP-006 / ADR-047: the rtp-core chart bridge is "
            + "platform-neutral; concrete platform MapBinding lives in "
            + "platform adapters.");
    rule.check(PRODUCTION_CLASSES);
  }

  @Test
  @DisplayName("commands.maps shall not call CompletableFuture#get / #join (REQ-RTP-F-008 / S-005)")
  void noBlockingFutures() {
    List<String> violations = new ArrayList<>();
    for (JavaClass clazz : PRODUCTION_CLASSES) {
      if (!clazz.getPackageName().startsWith("io.github.dailystruggle.rtp.common.commands.maps")) {
        continue;
      }
      for (JavaMethodCall call : clazz.getMethodCallsFromSelf()) {
        String owner = call.getTargetOwner().getFullName();
        String name = call.getName();
        boolean futureOwner =
            owner.equals("java.util.concurrent.CompletableFuture")
                || owner.equals("java.util.concurrent.Future");
        if (futureOwner && (name.equals("get") || name.equals("join"))) {
          violations.add(clazz.getFullName() + " calls " + owner + "#" + name);
        }
      }
    }
    assertTrue(violations.isEmpty(),
        "REQ-RTP-MAP-006: blocking future calls under commands.maps: " + violations);
  }
}
