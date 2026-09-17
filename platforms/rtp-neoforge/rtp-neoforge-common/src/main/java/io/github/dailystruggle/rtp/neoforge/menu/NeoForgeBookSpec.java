package io.github.dailystruggle.rtp.neoforge.menu;

import io.github.dailystruggle.rtp.api.menu.BookSpec;

import java.util.List;

/**
 * NeoForge view of {@link BookSpec}.
 */
public final class NeoForgeBookSpec extends BookSpec {

    public NeoForgeBookSpec(String title, List<Page> pages) {
        super(title, pages);
    }

    public static NeoForgeBookSpec from(BookSpec spec) {
        if (spec instanceof NeoForgeBookSpec n) return n;
        return new NeoForgeBookSpec(spec.title(), spec.pages());
    }
}
