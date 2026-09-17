package io.github.dailystruggle.rtp.fabric.menu;

import io.github.dailystruggle.rtp.api.menu.BookSpec;

import java.util.List;

/**
 * Fabric view of {@link BookSpec} (rtp-fabric-ADR-012 section 4).
 */
public final class FabricBookSpec extends BookSpec {

    public FabricBookSpec(String title, List<Page> pages) {
        super(title, pages);
    }

    public static FabricBookSpec from(BookSpec spec) {
        if (spec instanceof FabricBookSpec f) return f;
        return new FabricBookSpec(spec.title(), spec.pages());
    }
}
