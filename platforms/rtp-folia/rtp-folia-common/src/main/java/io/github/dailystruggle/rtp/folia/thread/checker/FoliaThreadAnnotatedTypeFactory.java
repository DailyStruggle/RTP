package io.github.dailystruggle.rtp.folia.thread.checker;

import io.github.dailystruggle.rtp.folia.thread.AnyThread;
import io.github.dailystruggle.rtp.folia.thread.AsyncThread;
import io.github.dailystruggle.rtp.folia.thread.GlobalRegionThread;
import io.github.dailystruggle.rtp.folia.thread.RegionThread;
import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import org.checkerframework.common.basetype.BaseAnnotatedTypeFactory;
import org.checkerframework.common.basetype.BaseTypeChecker;

/**
 * Annotated type factory for the {@link FoliaThreadChecker}.
 *
 * <p>Registers the four thread-affinity qualifiers and builds the qualifier lattice:
 * <pre>
 *   @AnyThread  (top — marked with @DefaultQualifierInHierarchy)
 *       |
 *   @RegionThread   @AsyncThread   @GlobalRegionThread  (incomparable leaves via @SubtypeOf)
 * </pre>
 *
 * <p>The qualifier hierarchy is derived automatically from the {@code @SubtypeOf} meta-annotations
 * on each qualifier annotation. No manual {@code createQualifierHierarchy} override is needed.
 */
public class FoliaThreadAnnotatedTypeFactory extends BaseAnnotatedTypeFactory {

    public FoliaThreadAnnotatedTypeFactory(BaseTypeChecker checker) {
        super(checker);
        this.postInit();
    }

    @Override
    protected Set<Class<? extends Annotation>> createSupportedTypeQualifiers() {
        return new LinkedHashSet<>(Arrays.asList(
                AnyThread.class,
                RegionThread.class,
                AsyncThread.class,
                GlobalRegionThread.class));
    }
}
