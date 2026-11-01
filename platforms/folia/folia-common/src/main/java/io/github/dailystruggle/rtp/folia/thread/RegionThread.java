package io.github.dailystruggle.rtp.folia.thread;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.checkerframework.framework.qual.SubtypeOf;

/**
 * Declares that the annotated method or constructor must execute exclusively on a Folia
 * {@code RegionThread} - the geographically-isolated thread that owns a specific chunk region.
 *
 * <p><b>Thread-affinity contract</b>
 * <ul>
 *   <li>A {@code @RegionThread} method may only be <em>called directly</em> from another
 *       {@code @RegionThread} method.</li>
 *   <li>Crossing into this context from any other thread type requires an explicit bridge via
 *       {@code Bukkit.getRegionScheduler().run(...)}, which acts as the hardware-interrupt
 *       analogue: it enqueues work onto the correct region's execution queue.</li>
 *   <li>Calling an {@link AsyncThread} or {@link GlobalRegionThread} method directly from a
 *       {@code @RegionThread} method is a compile-time error when the Checker Framework
 *       {@code FoliaThreadChecker} is active.</li>
 * </ul>
 *
 * <p><b>Checker Framework integration</b>
 * This annotation is a {@link SubtypeOf} qualifier in the {@code FoliaThreadType} hierarchy:
 * <pre>
 *   {@literal @}AnyThread  (top / unknown)
 *        |
 *   {@literal @}RegionThread  |  {@literal @}GlobalRegionThread  |  {@literal @}AsyncThread
 * </pre>
 * The checker rejects any call-site where the receiver thread type is not a subtype of the
 * callee's required thread type.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.TYPE})
@SubtypeOf(AnyThread.class)
public @interface RegionThread {}
