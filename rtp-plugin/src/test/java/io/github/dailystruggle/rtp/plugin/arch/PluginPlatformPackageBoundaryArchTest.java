package io.github.dailystruggle.rtp.plugin.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * MULTI_PLATFORM_PLAN Phase 2 — ArchUnit guard for disjoint
 * {@code io.github.dailystruggle.rtp.bukkit.**} and
 * {@code io.github.dailystruggle.rtp.fabric.**} package trees inside
 * {@code rtp-plugin}.
 *
 * <p>{@code rtp-plugin} ships the Bukkit-family entry point ({@code RTPBukkitPlugin})
 * and the Fabric mod entry point ({@code RTPFabricMod}) on a single classpath.
 * The two platform sub-trees must not cross-import: a Bukkit-tree class that
 * references {@code net.minecraft.**} or any {@code rtp.fabric.**} type, or a
 * Fabric-tree class that references {@code org.bukkit.**} or any
 * {@code rtp.bukkit.**} type, breaks the dual-runtime invariant (one JAR loads
 * on either platform; the other platform's classes stay unreferenced and the
 * classloader never trips a {@code NoClassDefFoundError}).
 *
 * <p>Scope: imports only the {@code rtp-plugin} module's own compiled output.
 * Cross-module Fabric types under {@code rtp.fabric.*} that live in
 * {@code rtp-fabric-common} (e.g. {@code rtp.fabric.player.FabricRTPPlayer})
 * are deliberately out of scope here — those modules already enforce their
 * own boundaries via their own ArchUnit suites.
 *
 * <p>Companion to {@code MetricsConsolidationArchTest}; the import-filter
 * pattern is shared (RTP-rooted URI predicate restricted to this module's
 * build output).
 */
class PluginPlatformPackageBoundaryArchTest {

    /**
     * Restrict the import to classes compiled out of this Gradle module
     * ({@code rtp-plugin/build/classes/...}). Without this filter ArchUnit
     * also picks up {@code rtp-fabric-common} and the {@code rtp-bukkit-*}
     * sibling modules from the test classpath, which legitimately straddle
     * the {@code rtp.fabric.**} / {@code rtp.bukkit.**} prefixes.
     */
    private static final ImportOption RTP_PLUGIN_MODULE_ONLY = location -> {
        String uri = location.asURI().toString();
        return (uri.contains("rtp-plugin/build/classes")
                || uri.contains("rtp-plugin\\build\\classes"))
                && (uri.contains("io/github/dailystruggle/rtp")
                || uri.contains("io\\github\\dailystruggle\\rtp"));
    };

    private static JavaClasses importPluginClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(RTP_PLUGIN_MODULE_ONLY)
                .importPackages("io.github.dailystruggle.rtp");
    }

    @Test
    void bukkit_subtree_must_not_depend_on_fabric_subtree() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("io.github.dailystruggle.rtp.bukkit..")
                .should().dependOnClassesThat()
                .resideInAPackage("io.github.dailystruggle.rtp.fabric..");
        rule.check(importPluginClasses());
    }

    @Test
    void fabric_subtree_must_not_depend_on_bukkit_subtree() {
        // Narrow exception: io.github.dailystruggle.rtp.bukkit.commands.test.TestCmd
        // is the *platform-neutral* `rtp test` parent verb (verified to have zero
        // org.bukkit.*, rtp.bukkitplatform.*, or commandsapi.bukkit.* imports).
        // Its location under the `bukkit.commands.test` package is a legacy
        // artifact predating the Fabric platform; RTPFabricMod intentionally
        // constructs it as documented in that file (~line 460-469). Relocating
        // it is out of scope for the dual-runtime guard — see MULTI_PLATFORM_PLAN
        // Phase 2 "Step G2 follow-up". The exception is keyed by FQN so any
        // future bukkit-tree class that creeps into Fabric init still trips
        // the guard. Sibling Bukkit-only TestCmd subcommands stay forbidden.
        com.tngtech.archunit.base.DescribedPredicate<com.tngtech.archunit.core.domain.JavaClass> inBukkitSubtreeExceptLegacyTestCmd =
                new com.tngtech.archunit.base.DescribedPredicate<>(
                        "reside in 'io.github.dailystruggle.rtp.bukkit..' except the legacy platform-neutral TestCmd") {
                    @Override
                    public boolean test(com.tngtech.archunit.core.domain.JavaClass c) {
                        String n = c.getName();
                        if (!n.startsWith("io.github.dailystruggle.rtp.bukkit.")) return false;
                        return !n.equals("io.github.dailystruggle.rtp.bukkit.commands.test.TestCmd");
                    }
                };
        ArchRule rule = noClasses()
                .that().resideInAPackage("io.github.dailystruggle.rtp.fabric..")
                .should().dependOnClassesThat(inBukkitSubtreeExceptLegacyTestCmd);
        rule.check(importPluginClasses());
    }

    @Test
    void bukkit_subtree_must_not_depend_on_net_minecraft() {
        // Bukkit-family code must route through org.bukkit.*; touching
        // net.minecraft.* in the Bukkit tree is the dual-runtime regression
        // this plan-item exists to guard.
        ArchRule rule = noClasses()
                .that().resideInAPackage("io.github.dailystruggle.rtp.bukkit..")
                .should().dependOnClassesThat()
                .resideInAPackage("net.minecraft..");
        rule.check(importPluginClasses());
    }

    @Test
    void fabric_subtree_must_not_depend_on_org_bukkit() {
        // The mirror: Fabric mod code must not pull in any org.bukkit type
        // (Bukkit is not on the classpath at Fabric runtime).
        ArchRule rule = noClasses()
                .that().resideInAPackage("io.github.dailystruggle.rtp.fabric..")
                .should().dependOnClassesThat()
                .resideInAPackage("org.bukkit..");
        rule.check(importPluginClasses());
    }
}
