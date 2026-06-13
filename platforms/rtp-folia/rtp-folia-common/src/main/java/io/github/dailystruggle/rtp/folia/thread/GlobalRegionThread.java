package io.github.dailystruggle.rtp.folia.thread;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.checkerframework.framework.qual.SubtypeOf;

/**
 * Declares that the annotated method or constructor must execute on Folia's
 * {@code GlobalRegionThread} — the single thread managed by
 * {@code Bukkit.getGlobalRegionScheduler()}.
 *
 * <p><b>Thread-affinity contract</b>
 * <ul>
 *   <li>The global region thread is a singleton execution context for server-wide, non-spatial
 *       state (e.g., scoreboard, global game rules). It is <em>not</em> a region thread and
 *       does <em>not</em> own any chunk.</li>
 *   <li>Economy / Vault interactions may be annotated with {@code @GlobalRegionThread} when
 *       they do not perform blocking I/O; prefer {@link AsyncThread} for any database work.</li>
 *   <li>A {@link RegionThread} method must not call a {@code @GlobalRegionThread} method
 *       directly; it must post work via {@code Bukkit.getGlobalRegionScheduler().run(...)}.</li>
 * </ul>
 *
 * <p><b>Systems engineering analogy</b>
 * The global region thread is analogous to a privileged kernel thread that arbitrates shared
 * global state. Region threads are user-space processes that must use a syscall (the scheduler
 * API) to request global-state mutations — direct access is forbidden.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.TYPE})
@SubtypeOf(AnyThread.class)
public @interface GlobalRegionThread {}
