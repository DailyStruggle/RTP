package io.github.dailystruggle.rtp.bukkit.metrics;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * C6 / section 1.6.5 of {@code CHECKLIST-metrics-and-multiserver.md} - ArchUnit
 * drift guard for the cross-platform metrics consolidation.
 *
 * <p>After section 1.6.1-section 1.6.4 the canonical sources of TPS / player-count /
 * soft-cap are the {@code Metrics.snapshot()} fields and the platform
 * {@code *MetricsBinding} samplers. This guard pins raw reads of the
 * underlying server APIs to those binding/sampler classes so future drift
 * can't silently reintroduce the divergence the proposal closed.
 *
 * <p>Allow-list (per proposal section 1.6.5):
 * <ul>
 *     <li>{@code Bukkit.getOnlinePlayers().size()} - {@code *MetricsBinding},
 *         {@code *TpsSampler}.</li>
 *     <li>{@code Server#getMaxPlayers()} / {@code Bukkit.getMaxPlayers()} -
 *         {@code *MetricsBinding}, {@code *TpsSampler}.</li>
 *     <li>{@code Server#getTPS()} - {@code *MetricsBinding} only
 *         ({@code RTPCostMetricsCharts} dropped from the list once section 1.6.4
 *         landed).</li>
 * </ul>
 *
 * <p>Out of scope (intentional, per proposal): iterator reads
 * ({@code for (Player p : Bukkit.getOnlinePlayers())}); the
 * {@code helpers/}, {@code commands-api/}, and {@code addons/} trees; and
 * reflective {@code MinecraftServer.getMaxPlayers()} probes inside
 * {@code FabricMetricsBinding} (the binding-boundary entry point per
 * section 1.6.3).
 *
 * <p>Scope rationale: the guard runs from {@code rtp-plugin}'s test
 * suite because that module is the only one that aggregates every
 * platform-common (rtp-bukkit-common, rtp-paper-common, rtp-folia-common,
 * rtp-fabric-common) plus rtp-core on a single classpath. Hosting the
 * guard in rtp-core would only see rtp-core classes; running it under
 * each platform module would multiply maintenance.
 */
class MetricsConsolidationArchTest {

    /**
     * Skip ArchUnit-internal types and any class outside the RTP root
     * package - we don't care about transitive deps like Bukkit / Folia /
     * MockBukkit themselves; the guard targets RTP-authored code only.
     */
    private static final ImportOption RTP_ONLY = location -> {
        String uri = location.asURI().toString();
        return uri.contains("io/github/dailystruggle/rtp")
                || uri.contains("io\\github\\dailystruggle\\rtp");
    };

    /**
     * Helper - import every RTP class on the test classpath. We deliberately
     * scan the entire {@code io.github.dailystruggle.rtp} root rather than
     * sub-packages so the guard catches future module additions automatically.
     */
    private static com.tngtech.archunit.core.domain.JavaClasses importRtpClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(RTP_ONLY)
                .importPackages("io.github.dailystruggle.rtp");
    }

    /**
     * Allow-list predicate: a class is exempt from the raw-call ban if its
     * simple name ends with {@code MetricsBinding} or {@code TpsSampler},
     * OR if it lives in the {@code .metrics} subpackage of an addon /
     * commands-api / helpers tree (which the proposal exempts outright).
     */
    private static boolean isAllowed(JavaClass clazz, boolean allowSampler) {
        String name = clazz.getSimpleName();
        if (name.endsWith("MetricsBinding")) return true;
        if (allowSampler && name.endsWith("TpsSampler")) return true;
        String pkg = clazz.getPackageName();
        // The guard is RTP-scoped already (RTP_ONLY); these subtrees are
        // listed for clarity even though most of them are out of the import
        // set.
        if (pkg.startsWith("io.github.dailystruggle.helpers")) return true;
        if (pkg.contains(".addons.")) return true;
        return false;
    }

    @Test
    void server_getTPS_reflective_read_is_restricted_to_metrics_bindings() {
        // Catches both `Server#getTPS` (Paper/Spigot) and `Server#getTPS()`
        // invoked reflectively via Method#invoke - the latter is the
        // RTPCostMetricsCharts pattern section 1.6.4 removed; the former is the
        // legitimate PaperMetricsBinding sampler call. Sampler classes are
        // NOT allowed here (proposal: only *MetricsBinding may call getTPS),
        // mirroring the audit decision in section 1.6.5.
        ArchRule rule = noClasses()
                .that(new com.tngtech.archunit.base.DescribedPredicate<>("are not *MetricsBinding") {
                    @Override
                    public boolean test(JavaClass c) {
                        return !isAllowed(c, false);
                    }
                })
                .should().callMethodWhere(
                        target(nameMatching("getTPS"))
                                .and(target(owner(assignableTo("org.bukkit.Server"))))
                )
                .because("sections 1.6.4/1.6.5 — Server#getTPS may only be called from a *MetricsBinding."
                        + " All other call sites must read Metrics.snapshot().tps1m.");
        rule.check(importRtpClasses());
    }

    /**
     * Phase F of the metrics-api extraction - module-boundary guard.
     *
     * <p>The canonical SPI types now live in the {@code metrics-api} module under
     * {@code io.github.dailystruggle.metrics.api.*}. A deprecated shim interface
     * {@code io.github.dailystruggle.rtp.common.metrics.MetricsBinding} remains in
     * {@code rtp-core} for one-cycle source compatibility, but no <em>new</em>
     * RTP code should import it: new bindings, samplers, dispatchers, and readers
     * must target the canonical {@code metrics-api} types directly.
     *
     * <p>This rule pins that drift: any RTP class outside the documented
     * compatibility allow-list that depends on the deprecated shim package is a
     * defect. Allow-list (transitional):
     * <ul>
     *     <li>The shim file itself ({@code rtp.common.metrics.MetricsBinding}).</li>
     *     <li>The four platform binding implementations
     *         ({@code BukkitTpsSampler}, {@code PaperMetricsBinding},
     *         {@code FoliaMetricsBinding}, {@code FabricMetricsBinding}) and the
     *         in-plugin {@code MetricsBindingDispatcher} - these still
     *         {@code implements MetricsBinding} via the shim and are migrated
     *         in lockstep when the shim is removed.</li>
     *     <li>Test scaffolding under {@code rtp-plugin} that exercises the shim
     *         path ({@code MetricsBindingDispatcherTest},
     *         {@code RTPServerAccessorTpsParityTest}) - kept as regression
     *         coverage for the compat layer.</li>
     *     <li>Doc references in {@code rtp-core.RTP} javadoc and
     *         {@code InfoCmdTest} - non-bytecode (javadoc / fully-qualified
     *         literal in test source) hits that ArchUnit does not see as
     *         dependencies; harmless to leave but listed for clarity.</li>
     * </ul>
     *
     * <p>Removal trigger: when every platform binding is migrated to declare
     * {@code implements io.github.dailystruggle.metrics.api.MetricsBinding}
     * directly, this rule's allow-list collapses to the shim file alone and the
     * shim can be deleted. Until then, the rule keeps the deprecated package
     * from spreading.
     */
    @Test
    void metrics_api_module_boundary_no_new_shim_imports() {
        ArchRule rule = noClasses()
                .that(new com.tngtech.archunit.base.DescribedPredicate<>(
                        "are not on the metrics-api shim compatibility allow-list") {
                    @Override
                    public boolean test(JavaClass c) {
                        String fqn = c.getFullName();
                        String simple = c.getSimpleName();
                        // The shim itself.
                        if (fqn.equals("io.github.dailystruggle.rtp.common.metrics.MetricsBinding"))
                            return false;
                        // Active platform binding impls + dispatcher (migrate in lockstep).
                        if (simple.equals("BukkitTpsSampler")
                                || simple.equals("PaperMetricsBinding")
                                || simple.equals("FoliaMetricsBinding")
                                || simple.equals("FabricMetricsBinding")
                                || simple.equals("MetricsBindingDispatcher"))
                            return false;
                        return true;
                    }
                })
                .should().dependOnClassesThat().haveFullyQualifiedName(
                        "io.github.dailystruggle.rtp.common.metrics.MetricsBinding")
                .because("Phase F — canonical SPI is io.github.dailystruggle.metrics.api.MetricsBinding."
                        + " The deprecated rtp.common.metrics.MetricsBinding shim is restricted to the"
                        + " four platform binding impls and the dispatcher pending shim removal.");
        rule.check(importRtpClasses());
    }

    /**
     * Phase F module-boundary guard - implementations of the canonical
     * {@code io.github.dailystruggle.metrics.api.MetricsBinding} must live in
     * a platform adapter module, not in {@code rtp-core} or {@code rtp-api}.
     *
     * <p>This pins the architecture boundary documented in AGENTS.md
     * <em>Architecture Boundaries</em>: platform-specific behaviour (chunk I/O,
     * TPS sampling, region enumeration) belongs in the platform adapter, not in
     * core. The {@code NOOP} default lives on the interface itself in
     * {@code metrics-api} and is exempt.
     */
    @Test
    void metrics_binding_implementations_live_in_platform_adapters() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(
                        "io.github.dailystruggle.rtp.common..",
                        "io.github.dailystruggle.rtp.api..")
                .and(new com.tngtech.archunit.base.DescribedPredicate<>(
                        "are not interfaces or abstract") {
                    @Override
                    public boolean test(JavaClass c) {
                        return !c.getModifiers().contains(
                                com.tngtech.archunit.core.domain.JavaModifier.ABSTRACT);
                    }
                })
                .should().implement("io.github.dailystruggle.metrics.api.MetricsBinding")
                .because("Phase F — MetricsBinding implementations must live in a platform"
                        + " adapter module (rtp-bukkit/rtp-paper/rtp-folia/rtp-fabric) or in"
                        + " metrics-api itself, never in rtp-core or rtp-api.");
        rule.check(importRtpClasses());
    }

    @Test
    void server_getMaxPlayers_is_restricted_to_metrics_bindings_or_samplers() {
        // section 1.6.5 - Bukkit.getMaxPlayers() / Server#getMaxPlayers() is
        // allow-listed inside *MetricsBinding and *TpsSampler; everything
        // else (notably RTPBukkitPlugin's login-cache bootstrap as of section 1.6.3)
        // must read snapshot().softCap. Bukkit/Folia iterator-style reads on
        // OnlinePlayers are NOT covered by this rule.
        ArchRule rule = noClasses()
                .that(new com.tngtech.archunit.base.DescribedPredicate<>("are not *MetricsBinding or *TpsSampler") {
                    @Override
                    public boolean test(JavaClass c) {
                        return !isAllowed(c, true);
                    }
                })
                .should().callMethodWhere(
                        target(nameMatching("getMaxPlayers"))
                                .and(target(owner(assignableTo("org.bukkit.Server"))))
                )
                .because("sections 1.6.3/1.6.5 — Server#getMaxPlayers may only be called from"
                        + " a *MetricsBinding or *TpsSampler. All other call sites must read"
                        + " Metrics.snapshot().softCap.");
        rule.check(importRtpClasses());
    }
}
