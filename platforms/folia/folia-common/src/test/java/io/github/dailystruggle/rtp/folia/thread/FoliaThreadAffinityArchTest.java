package io.github.dailystruggle.rtp.folia.thread;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ArchUnit rules that enforce Folia thread-affinity boundaries at the bytecode level.
 *
 * <p>These rules complement the Checker Framework's compile-time analysis with a second,
 * independent enforcement layer that operates on compiled {@code .class} files. Running both
 * tools in CI provides defence-in-depth: the Checker Framework catches violations during
 * {@code javac}; ArchUnit catches any that slip through (e.g., generated code, reflection
 * wrappers, or code compiled without the annotation processor).
 *
 * <h2>Systems engineering framing</h2>
 * Think of each thread context as a hardware memory-protection ring:
 * <ul>
 *   <li>{@code @RegionThread} - Ring 3 (user space, spatially isolated per chunk region)</li>
 *   <li>{@code @AsyncThread} - Ring 1 (I/O driver space; economy/Vault, database)</li>
 *   <li>{@code @GlobalRegionThread} - Ring 0 (kernel space; global server state)</li>
 * </ul>
 * A Ring-3 process cannot directly invoke a Ring-1 or Ring-0 routine; it must issue a
 * syscall (submit a lambda to the appropriate Folia scheduler). ArchUnit enforces this by
 * inspecting the bytecode dependency graph.
 */
class FoliaThreadAffinityArchTest {

    private static JavaClasses foliaClasses;

    @BeforeAll
    static void importClasses() {
        foliaClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.github.dailystruggle.rtp.folia");
    }

    // -----------------------------------------------------------------------
    // Rule 1 - Economy / Vault isolation
    // @RegionThread classes must not import or reference Vault Economy classes.
    // Vault's net.milkbowl.vault.economy package is the "Ring-1 I/O driver space";
    // direct access from a region thread bypasses the async scheduler bridge.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("@RegionThread classes must not depend on Vault Economy API")
    void regionThreadClassesMustNotDependOnVaultEconomy() {
        ArchRule rule = noClasses()
                .that().areAnnotatedWith(RegionThread.class)
                .should().dependOnClassesThat()
                .resideInAPackage("net.milkbowl.vault.economy..")
                .because(
                        "@RegionThread methods run on a geographically-isolated chunk-region "
                        + "thread. Vault Economy calls require database I/O and must be "
                        + "dispatched via Bukkit.getAsyncScheduler() to an @AsyncThread. "
                        + "Direct access would cause a ThreadAccessException at runtime.");

        rule.allowEmptyShould(true).check(foliaClasses);
    }

    // -----------------------------------------------------------------------
    // Rule 2 - @RegionThread methods must not call @AsyncThread methods directly.
    // The only legal crossing is via a scheduler-bridge lambda.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("@RegionThread methods must not directly call @AsyncThread methods")
    void regionThreadMethodsMustNotCallAsyncThreadMethodsDirectly() {
        ArchRule rule = noClasses()
                .that().containAnyMethodsThat(
                        DescribedPredicate.<JavaMethod>describe(
                                "annotated with @RegionThread",
                                m -> m.isAnnotatedWith(RegionThread.class)))
                .should().callMethodWhere(
                        DescribedPredicate.<JavaMethodCall>describe(
                                "target is annotated with @AsyncThread",
                                call -> call.getTarget().isAnnotatedWith(AsyncThread.class)))
                .because(
                        "A @RegionThread method must not directly invoke an @AsyncThread method. "
                        + "Submit the work via Bukkit.getAsyncScheduler().runNow(plugin, task) "
                        + "to cross the thread-context boundary safely.");

        rule.check(foliaClasses);
    }

    // -----------------------------------------------------------------------
    // Rule 3 - @RegionThread methods must not call @GlobalRegionThread methods directly.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("@RegionThread methods must not directly call @GlobalRegionThread methods")
    void regionThreadMethodsMustNotCallGlobalRegionThreadMethodsDirectly() {
        ArchRule rule = noClasses()
                .that().containAnyMethodsThat(
                        DescribedPredicate.<JavaMethod>describe(
                                "annotated with @RegionThread",
                                m -> m.isAnnotatedWith(RegionThread.class)))
                .should().callMethodWhere(
                        DescribedPredicate.<JavaMethodCall>describe(
                                "target is annotated with @GlobalRegionThread",
                                call -> call.getTarget().isAnnotatedWith(GlobalRegionThread.class)))
                .because(
                        "A @RegionThread method must not directly invoke a @GlobalRegionThread "
                        + "method. Submit the work via "
                        + "Bukkit.getGlobalRegionScheduler().run(plugin, task) instead.");

        rule.check(foliaClasses);
    }

    // -----------------------------------------------------------------------
    // Rule 4 - @AsyncThread methods must not depend on Folia RegionScheduler directly.
    // Economy/Vault code running on the async pool must not attempt to acquire a region lock.
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("@AsyncThread classes must not depend on Folia RegionScheduler")
    void asyncThreadClassesMustNotDependOnRegionScheduler() {
        ArchRule rule = noClasses()
                .that().areAnnotatedWith(AsyncThread.class)
                .should().dependOnClassesThat()
                .haveFullyQualifiedName(
                        "io.papermc.paper.threadedregions.scheduler.RegionScheduler")
                .because(
                        "@AsyncThread code runs on the Folia async pool and must not attempt "
                        + "to schedule work directly onto a RegionScheduler. Use the "
                        + "RegionScheduler only from a @RegionThread context.");

        rule.allowEmptyShould(true).check(foliaClasses);
    }
}
