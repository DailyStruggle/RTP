package io.github.dailystruggle.rtp.folia.thread;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.checkerframework.framework.qual.SubtypeOf;

/**
 * Declares that the annotated method or constructor must execute on a Folia {@code AsyncThread} —
 * a thread managed by {@code Bukkit.getAsyncScheduler()}.
 *
 * <p><b>Thread-affinity contract</b>
 * <ul>
 *   <li>Economy / Vault transactions and any blocking database I/O <strong>must</strong> be
 *       annotated with {@code @AsyncThread} (or {@link GlobalRegionThread}).</li>
 *   <li>A {@code @RegionThread} method <strong>must not</strong> call an {@code @AsyncThread}
 *       method directly. The only legal bridge is submitting a lambda to
 *       {@code Bukkit.getAsyncScheduler().runNow(plugin, task)} — the scheduler acts as the
 *       explicit interrupt-vector crossing between the two isolated execution domains.</li>
 *   <li>Calling an {@code @AsyncThread} method from {@link AnyThread} context is permitted
 *       (the caller makes no affinity promise, so no boundary is crossed).</li>
 * </ul>
 *
 * <p><b>Systems engineering analogy</b>
 * Treat {@code @AsyncThread} as a separate hardware interrupt level. A region thread (level-N
 * interrupt handler) cannot directly invoke an async handler (level-M); it must post a message
 * to the async interrupt queue and return.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.TYPE})
@SubtypeOf(AnyThread.class)
public @interface AsyncThread {}
