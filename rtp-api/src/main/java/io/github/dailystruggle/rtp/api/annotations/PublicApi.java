package io.github.dailystruggle.rtp.api.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type, constructor, method, or field as part of the public API surface that is consumed
 * by addons, sibling plugins, or platform adapters rather than by the core codebase itself.
 *
 * <p>Such members frequently have no caller inside this repository, which causes static
 * "unused declaration" / dead-code analysis to flag them as removable even though they are an
 * intentional, supported extension point. Annotating them with {@code @PublicApi} documents that
 * intent and lets the IDE treat the declaration as an implicit usage (entry point), so genuine
 * dead code is no longer hidden among false positives.
 *
 * <p>This is a documentation/tooling marker only — it has no runtime behaviour. Use it sparingly
 * and only on declarations that are deliberately exposed for external consumption.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({
    ElementType.TYPE,
    ElementType.CONSTRUCTOR,
    ElementType.METHOD,
    ElementType.FIELD
})
public @interface PublicApi {}
