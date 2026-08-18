package io.github.dailystruggle.rtp.fabric.menu;

import io.github.dailystruggle.rtp.api.menu.MenuAction;
import io.github.dailystruggle.rtp.api.menu.MenuFragment;
import io.github.dailystruggle.rtp.api.menu.MenuLine;
import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuPage;
import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link FabricBookMenuRenderer} (rtp-fabric-ADR-012). Covers the pure
 * {@code MenuModel} → {@link FabricBookSpec} conversion (page/line/fragment
 * shape, click-command mapping, decorative fragments) and the chat-fallback
 * dispatch when no live Fabric server / player is present. No live server is
 * required; {@code RTP.serverAccessor} is null in the test JVM, so
 * {@code format(...)} degrades to passing the raw string through.
 */
class FabricBookMenuRendererTest {

    private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static FabricBookMenuRenderer newRenderer(MenuRenderer fallback) {
        return new FabricBookMenuRenderer(fallback);
    }

    private static MenuModel sampleModel() {
        MenuFragment clickable = new MenuFragment(
                "&aGo to plains", "click me",
                new MenuAction.RunRtpCommand(new String[]{"biome", "plains"}));
        MenuFragment decorative = MenuFragment.plain("&7just text");
        MenuLine line1 = new MenuLine(List.of(clickable, decorative));
        MenuLine line2 = MenuLine.of(MenuFragment.plain("&8footer"));
        MenuPage page = new MenuPage(List.of(line1, line2));
        return new MenuModel("Front Page", List.of(page));
    }

    @Test
    void buildSpecPreservesPageLineFragmentShape() {
        FabricBookSpec spec = newRenderer((id, m) -> {}).buildSpec(VIEWER, sampleModel());
        assertEquals("Front Page", spec.title());
        assertEquals(1, spec.pages().size());
        FabricBookSpec.Page page = spec.pages().get(0);
        assertEquals(2, page.lines().size());
        assertEquals(2, page.lines().get(0).fragments().size());
        assertEquals(1, page.lines().get(1).fragments().size());
    }

    @Test
    void clickableFragmentCarriesConcreteCommand() {
        FabricBookSpec spec = newRenderer((id, m) -> {}).buildSpec(VIEWER, sampleModel());
        FabricBookSpec.Fragment frag = spec.pages().get(0).lines().get(0).fragments().get(0);
        assertEquals("/rtp biome plains", frag.runCommand());
        assertEquals("click me", frag.hover());
        assertTrue(frag.text().contains("Go to plains"));
    }

    @Test
    void decorativeFragmentHasNoCommand() {
        FabricBookSpec spec = newRenderer((id, m) -> {}).buildSpec(VIEWER, sampleModel());
        FabricBookSpec.Fragment frag = spec.pages().get(0).lines().get(0).fragments().get(1);
        assertNull(frag.runCommand());
    }

    @Test
    void changePageMapsToConcretePageCommand() {
        MenuFragment next = new MenuFragment("next", null, new MenuAction.ChangePage(2));
        MenuModel model = new MenuModel("t",
                List.of(new MenuPage(List.of(MenuLine.of(next)))));
        FabricBookSpec spec = newRenderer((id, m) -> {}).buildSpec(VIEWER, model);
        assertEquals("/rtp menu page n=3",
                spec.pages().get(0).lines().get(0).fragments().get(0).runCommand());
    }

    @Test
    void rendersViaChatFallbackWhenNoServerPlayer() {
        // RTP.serverAccessor is null in the test JVM, so resolveHandle yields
        // no player and the renderer must delegate to the chat fallback.
        AtomicInteger calls = new AtomicInteger();
        MenuRenderer fallback = (id, m) -> calls.incrementAndGet();
        newRenderer(fallback).render(VIEWER, sampleModel());
        assertEquals(1, calls.get());
    }

    @Test
    void nullArgumentsAreNoOps() {
        AtomicInteger calls = new AtomicInteger();
        MenuRenderer fallback = (id, m) -> calls.incrementAndGet();
        FabricBookMenuRenderer renderer = newRenderer(fallback);
        renderer.render(null, sampleModel());
        renderer.render(VIEWER, null);
        assertEquals(0, calls.get());
        assertFalse(calls.get() > 0);
    }
}
