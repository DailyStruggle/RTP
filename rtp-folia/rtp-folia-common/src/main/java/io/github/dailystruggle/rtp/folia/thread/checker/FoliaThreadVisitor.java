package io.github.dailystruggle.rtp.folia.thread.checker;

import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.TreePath;
import io.github.dailystruggle.rtp.folia.thread.AsyncThread;
import io.github.dailystruggle.rtp.folia.thread.GlobalRegionThread;
import io.github.dailystruggle.rtp.folia.thread.RegionThread;
import javax.lang.model.element.ExecutableElement;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.common.basetype.BaseTypeVisitor;
import org.checkerframework.javacutil.TreePathUtil;
import org.checkerframework.javacutil.TreeUtils;

/**
 * AST visitor that enforces Folia thread-affinity rules at every method call-site.
 *
 * <h2>Enforcement model</h2>
 * Each method call is treated as a <em>context switch request</em>. The visitor checks whether
 * the caller's declared thread context is compatible with the callee's required context. If not,
 * it verifies that the call-site is inside a scheduler-bridge lambda — the only legal
 * "interrupt-vector" that may cross context boundaries.
 *
 * <h2>Rule 1 — Region isolation</h2>
 * A {@code @RegionThread} callee may only be reached from:
 * <ul>
 *   <li>another {@code @RegionThread} method (same context, no crossing), or</li>
 *   <li>a lambda argument to {@code RegionScheduler.run / runDelayed / runAtFixedRate}
 *       (explicit scheduler bridge).</li>
 * </ul>
 *
 * <h2>Rule 2 — Economy / Vault isolation</h2>
 * A {@code @RegionThread} caller must not directly invoke an {@code @AsyncThread} or
 * {@code @GlobalRegionThread} callee. The only legal bridge is submitting a lambda to
 * {@code Bukkit.getAsyncScheduler().runNow(...)}.
 *
 * <h2>Scheduler-bridge detection</h2>
 * The visitor walks the {@link TreePath} upward from the call-site. If it finds a
 * {@link LambdaExpressionTree} whose enclosing {@link MethodInvocationTree} targets one of the
 * Folia scheduler methods listed in {@link #SCHEDULER_BRIDGE_METHODS}, the crossing is
 * considered legal and no error is emitted.
 */
public class FoliaThreadVisitor extends BaseTypeVisitor<FoliaThreadAnnotatedTypeFactory> {

    /**
     * Fully-qualified method signatures that constitute legal scheduler bridges.
     * A call-site inside a lambda passed to any of these methods is exempt from the
     * cross-context prohibition.
     */
    private static final java.util.Set<String> SCHEDULER_BRIDGE_METHODS = java.util.Set.of(
            // RegionScheduler bridges (legal entry point for @RegionThread callees)
            "io.papermc.paper.threadedregions.scheduler.RegionScheduler.run",
            "io.papermc.paper.threadedregions.scheduler.RegionScheduler.runDelayed",
            "io.papermc.paper.threadedregions.scheduler.RegionScheduler.runAtFixedRate",
            // AsyncScheduler bridges (legal entry point for @AsyncThread callees)
            "io.papermc.paper.threadedregions.scheduler.AsyncScheduler.runNow",
            "io.papermc.paper.threadedregions.scheduler.AsyncScheduler.runAtFixedRate",
            // GlobalRegionScheduler bridges (legal entry point for @GlobalRegionThread callees)
            "io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler.run",
            "io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler.runDelayed",
            "io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler.runAtFixedRate"
    );

    public FoliaThreadVisitor(BaseTypeChecker checker) {
        super(checker);
    }

    @Override
    public Void visitMethodInvocation(MethodInvocationTree callTree, Void p) {
        ExecutableElement callee = TreeUtils.elementFromUse(callTree);
        if (callee == null) {
            return super.visitMethodInvocation(callTree, p);
        }

        boolean calleeIsRegion = callee.getAnnotation(RegionThread.class) != null;
        boolean calleeIsAsync = callee.getAnnotation(AsyncThread.class) != null;
        boolean calleeIsGlobal = callee.getAnnotation(GlobalRegionThread.class) != null;

        if (!calleeIsRegion && !calleeIsAsync && !calleeIsGlobal) {
            // Callee has no thread constraint — no check needed.
            return super.visitMethodInvocation(callTree, p);
        }

        // Determine the caller's thread context by walking up to the enclosing method.
        MethodTree enclosingMethod = TreePathUtil.enclosingMethod(getCurrentPath());
        if (enclosingMethod == null) {
            return super.visitMethodInvocation(callTree, p);
        }
        ExecutableElement caller = TreeUtils.elementFromDeclaration(enclosingMethod);
        boolean callerIsRegion = caller != null && caller.getAnnotation(RegionThread.class) != null;

        if (!callerIsRegion) {
            // Caller is @AnyThread, @AsyncThread, or @GlobalRegionThread.
            // Only @RegionThread callers are subject to the cross-context prohibition.
            return super.visitMethodInvocation(callTree, p);
        }

        // Caller is @RegionThread. Callee requires a different (incompatible) context.
        if (calleeIsAsync || calleeIsGlobal) {
            // Violation unless the call-site is inside a scheduler-bridge lambda.
            if (!isInsideSchedulerBridgeLambda(getCurrentPath())) {
                checker.reportError(
                        callTree,
                        "thread.affinity.violation",
                        caller.getSimpleName(),
                        callee.getSimpleName(),
                        calleeIsAsync ? "@AsyncThread" : "@GlobalRegionThread");
            }
        }

        return super.visitMethodInvocation(callTree, p);
    }

    /**
     * Returns {@code true} if the given path is nested inside a {@link LambdaExpressionTree}
     * that is itself an argument to one of the known Folia scheduler bridge methods.
     *
     * <p>This implements the "interrupt-vector whitelist": crossing a thread boundary is only
     * legal when the code is explicitly enqueued onto the target scheduler's queue.
     */
    private boolean isInsideSchedulerBridgeLambda(TreePath path) {
        TreePath current = path;
        while (current != null) {
            if (current.getLeaf() instanceof LambdaExpressionTree) {
                TreePath parent = current.getParentPath();
                if (parent != null && parent.getLeaf() instanceof MethodInvocationTree enclosingCall) {
                    ExecutableElement enclosingCallee = TreeUtils.elementFromUse(enclosingCall);
                    if (enclosingCallee != null) {
                        String fqn = enclosingCallee.getEnclosingElement().toString()
                                + "." + enclosingCallee.getSimpleName();
                        if (SCHEDULER_BRIDGE_METHODS.contains(fqn)) {
                            return true;
                        }
                    }
                }
            }
            current = current.getParentPath();
        }
        return false;
    }
}
