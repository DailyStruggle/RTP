package io.github.dailystruggle.rtp.folia.thread.checker;

import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.framework.qual.StubFiles;

/**
 * Checker Framework plug-in that enforces Folia thread-affinity boundaries at compile time.
 *
 * <h2>How to activate</h2>
 * Add the following to the {@code compileJava} task in the subproject's {@code build.gradle}:
 * <pre>{@code
 * dependencies {
 *     annotationProcessor 'org.checkerframework:checker:3.42.0'
 * }
 * tasks.withType(JavaCompile).configureEach {
 *     options.compilerArgs += [
 *         '-processor',
 *         'io.github.dailystruggle.rtp.folia.thread.checker.FoliaThreadChecker'
 *     ]
 * }
 * }</pre>
 *
 * <h2>Type hierarchy (AST / typestate model)</h2>
 * The checker treats each thread context as an isolated memory space — analogous to hardware
 * memory-protection rings. The qualifier lattice is:
 * <pre>
 *   @AnyThread          ← top (no constraint; default for unannotated code)
 *   ├── @RegionThread   ← geographically-isolated chunk-region execution context
 *   ├── @AsyncThread    ← Folia AsyncScheduler pool (I/O, economy/Vault)
 *   └── @GlobalRegionThread ← singleton global-tick thread
 * </pre>
 * The three leaf qualifiers are <em>incomparable siblings</em>: no subtype relationship exists
 * between them. The Checker Framework's standard assignment-compatibility rule therefore rejects
 * any direct call from a {@code @RegionThread} method to an {@code @AsyncThread} or
 * {@code @GlobalRegionThread} method, because the callee's required context is not a supertype
 * of the caller's context.
 *
 * <h2>Enforcement rules (encoded in {@link FoliaThreadVisitor})</h2>
 * <ol>
 *   <li><strong>Region isolation</strong> — a {@code @RegionThread} method may only be invoked
 *       from another {@code @RegionThread} method, or from inside a lambda passed to
 *       {@code Bukkit.getRegionScheduler().run(...)}. The visitor detects the scheduler-bridge
 *       pattern by inspecting the enclosing {@code MethodInvocationTree} for a call whose
 *       receiver type is {@code io.papermc.paper.threadedregions.scheduler.RegionScheduler}.</li>
 *   <li><strong>Economy isolation</strong> — a {@code @RegionThread} method must not directly
 *       call any method annotated {@code @AsyncThread}. The only legal crossing is via
 *       {@code Bukkit.getAsyncScheduler().runNow(...)}.</li>
 * </ol>
 *
 * <h2>AST traversal</h2>
 * {@link FoliaThreadVisitor} extends {@code BaseTypeVisitor} and overrides
 * {@code visitMethodInvocation}. For each call-site the visitor:
 * <ol>
 *   <li>Resolves the callee's declared thread qualifier from its annotation mirror.</li>
 *   <li>Resolves the caller's qualifier from the enclosing method's annotation.</li>
 *   <li>Checks qualifier compatibility using the lattice above.</li>
 *   <li>If incompatible, checks whether the call-site is inside a scheduler-bridge lambda
 *       (whitelisted crossing). If not, issues a {@code thread.affinity.violation} error.</li>
 * </ol>
 */
@StubFiles("folia-thread-stubs.astub")
public class FoliaThreadChecker extends BaseTypeChecker {

}
