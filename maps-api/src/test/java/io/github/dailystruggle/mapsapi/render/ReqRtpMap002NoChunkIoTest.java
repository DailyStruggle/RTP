package io.github.dailystruggle.mapsapi.render;

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
 * REQ-RTP-MAP-002 (extends REQ-RTP-F-008 / REQ-RTP-S-005): no class under
 * {@code mapsapi.render..} performs chunk I/O, blocks on
 * {@link java.util.concurrent.CompletableFuture#get} or
 * {@link java.util.concurrent.CompletableFuture#join}, or imports
 * {@code org.bukkit.*} / {@code net.minecraft.*}.
 *
 * <p>Also covers REQ-RTP-MAP-001 / REQ-RTP-S-006 hygiene: nothing in
 * {@code maps-api} production sources may reach into a platform package.
 */
@DisplayName("REQ-RTP-MAP-002 — maps-api renderers are platform-neutral and non-blocking")
class ReqRtpMap002NoChunkIoTest {

    private static final JavaClasses PRODUCTION_CLASSES =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages("io.github.dailystruggle.mapsapi");

    @Test
    @DisplayName("mapsapi.render shall not import org.bukkit.* / net.minecraft.* / paper / fabric")
    void rendersHaveNoPlatformImports() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("io.github.dailystruggle.mapsapi.render..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.bukkit..",
                        "io.papermc..",
                        "net.minecraft..",
                        "net.fabricmc..")
                .because("REQ-RTP-MAP-002: renderers are pure pixel functions; "
                        + "concrete platform bindings live in mapsapi.bukkit / mapsapi.fabric.");
        rule.check(PRODUCTION_CLASSES);
    }

    @Test
    @DisplayName("mapsapi.render shall not call CompletableFuture#get / #join (REQ-RTP-F-008 / S-005)")
    void rendersDoNotBlockOnFutures() {
        List<String> violations = new ArrayList<>();
        for (JavaClass clazz : PRODUCTION_CLASSES) {
            if (!clazz.getPackageName().startsWith("io.github.dailystruggle.mapsapi.render")) {
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
                "REQ-RTP-MAP-002: blocking future calls under mapsapi.render: " + violations);
    }

    @Test
    @DisplayName("mapsapi (whole module) shall not import org.bukkit.* or net.minecraft.* in Stage 1")
    void mapsApiStage1HasNoPlatformImports() {
        // Stage 2 will relax this to allow mapsapi.bukkit; Stage 3 will allow
        // mapsapi.fabric. Until those subpackages land, the entire module is
        // platform-neutral and ArchUnit asserts that.
        ArchRule rule = noClasses()
                .that().resideInAPackage("io.github.dailystruggle.mapsapi..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.bukkit..",
                        "net.minecraft..")
                .because("maps-api-ADR-001 §Package layout: Stage 1 ships SPI only; "
                        + "bukkit / fabric subpackages land in Stage 2 / 3.");
        rule.check(PRODUCTION_CLASSES);
    }
}
