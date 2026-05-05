package io.github.dailystruggle.rtp.folia.thread.checker;

import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.framework.qual.StubFiles;

/**
 * Checker Framework plug-in enforcing Folia thread-affinity boundaries at compile time.
 *
 * <p>To activate, add to the subproject's {@code build.gradle}:
 * <pre>{@code
 * dependencies { annotationProcessor 'org.checkerframework:checker:3.42.0' }
 * tasks.withType(JavaCompile).configureEach {
 *     options.compilerArgs += ['-processor',
 *         'io.github.dailystruggle.rtp.folia.thread.checker.FoliaThreadChecker']
 * }
 * }</pre>
 *
 * <p>Qualifier lattice ({@code @AnyThread} on top; the three leaves are incomparable):
 * <pre>
 *   @AnyThread
 *   ├── @RegionThread        — chunk-region execution context
 *   ├── @AsyncThread         — Folia AsyncScheduler pool (I/O, economy/Vault)
 *   └── @GlobalRegionThread  — singleton global-tick thread
 * </pre>
 *
 * <p>Enforcement (in {@link FoliaThreadVisitor}): a {@code @RegionThread} method may only be
 * called from another {@code @RegionThread} or from a lambda passed to
 * {@code Bukkit.getRegionScheduler().run(...)}; calls into {@code @AsyncThread} must cross via
 * {@code Bukkit.getAsyncScheduler().runNow(...)}. Violations are reported as
 * {@code thread.affinity.violation}.
 */
@StubFiles("folia-thread-stubs.astub")
public class FoliaThreadChecker extends BaseTypeChecker {

}
