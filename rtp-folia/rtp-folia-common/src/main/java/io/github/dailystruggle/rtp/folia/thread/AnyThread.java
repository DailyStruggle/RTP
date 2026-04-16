package io.github.dailystruggle.rtp.folia.thread;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.checkerframework.framework.qual.DefaultQualifierInHierarchy;
import org.checkerframework.framework.qual.SubtypeOf;

/**
 * Top-level qualifier in the {@code FoliaThreadType} hierarchy.
 *
 * <p>Represents an unknown or unconstrained thread context. All concrete thread qualifiers
 * ({@link RegionThread}, {@link AsyncThread}, {@link GlobalRegionThread}) are subtypes of this
 * annotation. Unannotated methods are implicitly {@code @AnyThread} — they make no thread-affinity
 * promise and may be called from any context.
 *
 * <p>Think of this as the "unmapped memory" region in a hardware memory-protection model: access
 * is permitted from anywhere, but no isolation guarantees are provided.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.TYPE})
@DefaultQualifierInHierarchy
@SubtypeOf({})
public @interface AnyThread {}
