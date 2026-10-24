package io.github.dailystruggle.menu.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.server.api.PlatformFamily;
import io.github.dailystruggle.server.api.ServerAccessor;
import io.github.dailystruggle.server.api.ServerAccessors;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for the declarative menu and prompt SPI in {@code io.github.dailystruggle.menu.api}. */
@DisplayName("Declarative Menu & Prompt SPI (ADR-035, ADR-045, ADR-050, ADR-070)")
class MenuApiTest {

  @AfterEach
  void tearDown() {
    ServerAccessors.set(null);
  }

  @Test
  @DisplayName("MenuAction variants enforce preconditions and normalize fields")
  void menuActionVariantsEnforcePreconditions() {
    MenuAction.RunCommand run = new MenuAction.RunCommand("rtp", "world");
    assertEquals(List.of("rtp", "world"), run.args());

    MenuAction.OpenSubmenu submenu = new MenuAction.OpenSubmenu("admin", "setup");
    assertEquals(List.of("admin", "setup"), submenu.path());

    MenuAction.ChangePage page = new MenuAction.ChangePage(3);
    assertEquals(3, page.pageIndex());
    assertThrows(IllegalArgumentException.class, () -> new MenuAction.ChangePage(-1));

    MenuAction.SuggestInput suggest = new MenuAction.SuggestInput("/rtp ");
    assertEquals("/rtp ", suggest.prefix());
    assertThrows(IllegalArgumentException.class, () -> new MenuAction.SuggestInput(""));

    MenuAction.PromptAnvilInput promptDefault =
        new MenuAction.PromptAnvilInput(List.of("rtp", "config"), "max", "1000");
    assertEquals(List.of("rtp", "config"), promptDefault.parentPath());
    assertEquals("max", promptDefault.paramName());
    assertEquals("1000", promptDefault.prefill());
    assertEquals(MenuAction.Mode.RUN, promptDefault.mode());

    MenuAction.PromptAnvilInput promptStaged =
        new MenuAction.PromptAnvilInput(
            List.of("config.yml"), "min", "0", MenuAction.Mode.STAGE);
    assertEquals(MenuAction.Mode.STAGE, promptStaged.mode());
    assertThrows(
        IllegalArgumentException.class,
        () -> new MenuAction.PromptAnvilInput(List.of(), "", "0"));

    MenuAction.OpenExternalUri httpsUri =
        new MenuAction.OpenExternalUri(URI.create("https://example.com/docs"));
    assertEquals(URI.create("https://example.com/docs"), httpsUri.uri());
    assertThrows(
        IllegalArgumentException.class,
        () -> new MenuAction.OpenExternalUri(URI.create("http://example.com")));

    MenuAction.StageValue stage = new MenuAction.StageValue("config.yml", "max", "100");
    assertEquals("config.yml", stage.scopeName());
    assertEquals("max", stage.key());
    assertEquals("100", stage.value());
    assertThrows(IllegalArgumentException.class, () -> new MenuAction.StageValue("", "k", "v"));
    assertThrows(IllegalArgumentException.class, () -> new MenuAction.StageValue("s", "", "v"));

    MenuAction.UnstageValue unstage = new MenuAction.UnstageValue("config.yml", "max");
    assertEquals("config.yml", unstage.scopeName());
    assertEquals("max", unstage.key());
    assertThrows(IllegalArgumentException.class, () -> new MenuAction.UnstageValue("", "k"));
    assertThrows(IllegalArgumentException.class, () -> new MenuAction.UnstageValue("s", ""));

    MenuAction.ApplyStagedValues apply = new MenuAction.ApplyStagedValues("config.yml");
    assertEquals("config.yml", apply.scopeName());
    assertThrows(IllegalArgumentException.class, () -> new MenuAction.ApplyStagedValues(""));

    MenuAction.DiscardStagedValues discard = new MenuAction.DiscardStagedValues("config.yml");
    assertEquals("config.yml", discard.scopeName());
    assertThrows(IllegalArgumentException.class, () -> new MenuAction.DiscardStagedValues(""));
  }

  @Test
  @DisplayName("MenuDescriptor builds immutable pages, lines, fragments, items, and metadata")
  void menuDescriptorInvariants() {
    MenuDescriptor.Fragment plain = MenuDescriptor.Fragment.plain("shape: ");
    assertEquals("shape: ", plain.text());
    assertNull(plain.hover());
    assertNull(plain.action());

    MenuAction clickAction = new MenuAction.RunCommand("rtp", "info");
    MenuDescriptor.Fragment interactive =
        new MenuDescriptor.Fragment("[SQUARE]", "Inspect", clickAction);
    assertEquals("[SQUARE]", interactive.text());
    assertEquals("Inspect", interactive.hover());
    assertSame(clickAction, interactive.action());

    MenuDescriptor.Line line = MenuDescriptor.Line.of(interactive);
    assertEquals(1, line.fragments().size());

    MenuDescriptor.Item item =
        new MenuDescriptor.Item(0, "Overworld", List.of("Click to teleport"), "GRASS_BLOCK", clickAction);
    assertEquals(0, item.slot());
    assertEquals("Overworld", item.label());
    assertEquals(List.of("Click to teleport"), item.lore());
    assertEquals("GRASS_BLOCK", item.iconKey());
    assertThrows(
        IllegalArgumentException.class,
        () -> new MenuDescriptor.Item(-1, "Bad", List.of(), null, null));

    MenuDescriptor.Page page = new MenuDescriptor.Page(List.of(line));
    assertEquals(1, page.lines().size());
    assertEquals(0, page.items().size());

    MenuDescriptor.Page itemPage = MenuDescriptor.Page.ofItems(List.of(item));
    assertEquals(1, itemPage.items().size());

    MenuDescriptor descriptor =
        MenuDescriptor.builder("LeafRTP Setup")
            .rows(4)
            .addPage(page)
            .addPage(itemPage)
            .style("theme", "book")
            .build();
    assertEquals("LeafRTP Setup", descriptor.title());
    assertEquals(4, descriptor.rows());
    assertEquals(2, descriptor.pages().size());
    assertEquals("book", descriptor.styleMetadata().get("theme"));

    MenuDescriptor simple = new MenuDescriptor("Simple", List.of(page));
    assertEquals(MenuDescriptor.DEFAULT_ROWS, simple.rows());
    assertEquals(Map.of(), simple.styleMetadata());

    assertThrows(
        IllegalArgumentException.class,
        () -> MenuDescriptor.builder("Bad").rows(0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MenuDescriptor("Title", 6, List.of(), Map.of()));
  }

  @Test
  @DisplayName("MenuItemClick validates viewer, indices, click type, and action")
  void menuItemClickValidation() {
    UUID viewer = UUID.randomUUID();
    MenuAction action = new MenuAction.RunCommand("rtp");

    MenuItemClick slotClick =
        MenuItemClick.ofSlot(viewer, 0, 4, MenuItemClick.ClickType.LEFT, action);
    assertEquals(viewer, slotClick.viewer());
    assertEquals(0, slotClick.pageIndex());
    assertEquals(4, slotClick.slot());
    assertEquals(MenuItemClick.ClickType.LEFT, slotClick.clickType());
    assertSame(action, slotClick.action());

    MenuItemClick fragClick = MenuItemClick.ofFragment(viewer, 1, action);
    assertEquals(-1, fragClick.slot());
    assertEquals(1, fragClick.pageIndex());

    assertThrows(
        IllegalArgumentException.class,
        () -> MenuItemClick.ofSlot(viewer, 0, -1, MenuItemClick.ClickType.RIGHT, action));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MenuItemClick(viewer, -1, 0, MenuItemClick.ClickType.LEFT, action));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MenuItemClick(viewer, 0, -2, MenuItemClick.ClickType.MIDDLE, action));
  }

  @Test
  @DisplayName("MenuSession tracks bounded navigation history and scoped staging state")
  void menuSessionNavigationAndStaging() {
    UUID viewer = UUID.randomUUID();
    assertThrows(IllegalArgumentException.class, () -> new MenuSession(viewer, 0));

    MenuSession defaultSession = new MenuSession(viewer);
    assertEquals(viewer, defaultSession.viewer());

    MenuSession session = new MenuSession(viewer, 2);
    assertEquals(viewer, session.viewer());
    assertTrue(session.current().isEmpty());
    assertTrue(session.currentDescriptor().isEmpty());
    assertEquals(0, session.currentPageIndex());
    assertFalse(session.canGoBack());
    assertTrue(session.pop().isEmpty());
    assertThrows(IllegalStateException.class, () -> session.setCurrentPageIndex(0));

    MenuDescriptor.Page page0 =
        new MenuDescriptor.Page(List.of(MenuDescriptor.Line.of(MenuDescriptor.Fragment.plain("p0"))));
    MenuDescriptor.Page page1 =
        new MenuDescriptor.Page(List.of(MenuDescriptor.Line.of(MenuDescriptor.Fragment.plain("p1"))));
    MenuDescriptor menuA = new MenuDescriptor("Menu A", List.of(page0, page1));
    MenuDescriptor menuB = new MenuDescriptor("Menu B", List.of(page0));
    MenuDescriptor menuC = new MenuDescriptor("Menu C", List.of(page0));

    assertThrows(
        IllegalArgumentException.class, () -> new MenuSession.NavigationEntry(menuA, 5));

    MenuSession.NavigationEntry pushedA = session.push(menuA, 99);
    assertEquals(1, pushedA.pageIndex());
    assertEquals(1, session.currentPageIndex());
    assertEquals(Optional.of(menuA), session.currentDescriptor());
    assertFalse(session.canGoBack());

    session.setCurrentPageIndex(0);
    assertEquals(0, session.currentPageIndex());
    assertThrows(IllegalArgumentException.class, () -> session.setCurrentPageIndex(-1));

    session.push(menuB, 0);
    assertEquals(2, session.historyDepth());
    assertTrue(session.canGoBack());

    session.push(menuC, 0);
    assertEquals(2, session.historyDepth());
    assertEquals(Optional.of(menuC), session.currentDescriptor());

    Optional<MenuSession.NavigationEntry> previous = session.pop();
    assertTrue(previous.isPresent());
    assertEquals(menuB, previous.get().descriptor());
    assertFalse(session.canGoBack());

    session.clearHistory();
    assertEquals(0, session.historyDepth());

    assertFalse(session.hasStagedChanges("config.yml"));
    assertEquals(Map.of(), session.stagedValues("config.yml"));
    assertFalse(session.unstage("config.yml", "shape"));

    session.stage("config.yml", "shape", "CIRCLE");
    session.stage("config.yml", "max", "2500");
    assertTrue(session.hasStagedChanges("config.yml"));
    assertEquals(
        Map.of("shape", "CIRCLE", "max", "2500"), session.stagedValues("config.yml"));

    assertTrue(session.unstage("config.yml", "shape"));
    assertFalse(session.unstage("config.yml", "shape"));
    assertTrue(session.unstage("config.yml", "max"));
    assertFalse(session.hasStagedChanges("config.yml"));

    session.stage("regions.yml", "mode", "ACCUMULATED");
    Map<String, String> cleared = session.clearStaged("regions.yml");
    assertEquals(Map.of("mode", "ACCUMULATED"), cleared);
    assertEquals(Map.of(), session.clearStaged("regions.yml"));

    session.stage("a.yml", "k", "v");
    session.clearAllStaged();
    assertFalse(session.hasStagedChanges("a.yml"));

    assertThrows(IllegalArgumentException.class, () -> session.stage("", "k", "v"));
    assertThrows(IllegalArgumentException.class, () -> session.stage("a.yml", "", "v"));
  }

  @Test
  @DisplayName("MenuTokenService enforces viewer ownership, TTL expiry, one-time use, and capacity")
  void menuTokenServiceLifecycle() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new MenuTokenService(0, Duration.ofSeconds(10), System::nanoTime));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MenuTokenService(4, Duration.ZERO, System::nanoTime));

    MenuTokenService defaultTokens = new MenuTokenService();
    assertEquals(0, defaultTokens.activeTokenCount());

    AtomicLong clock = new AtomicLong(1_000_000L);
    MenuTokenService tokens = new MenuTokenService(2, Duration.ofNanos(500L), clock::get);

    UUID viewer1 = UUID.randomUUID();
    UUID viewer2 = UUID.randomUUID();
    MenuAction action1 = new MenuAction.RunCommand("rtp");
    MenuAction action2 = new MenuAction.ChangePage(2);
    MenuAction action3 = new MenuAction.DiscardStagedValues("config.yml");

    String oneTime = tokens.issue(viewer1, action1);
    assertEquals(1, tokens.activeTokenCount());

    assertTrue(tokens.redeem(viewer2, oneTime).isEmpty());
    assertEquals(Optional.of(action1), tokens.redeem(viewer1, oneTime));
    assertTrue(tokens.redeem(viewer1, oneTime).isEmpty());

    String multiUse = tokens.issue(viewer1, action2, false);
    assertEquals(Optional.of(action2), tokens.redeem(viewer1, multiUse));
    assertEquals(Optional.of(action2), tokens.redeem(viewer1, multiUse));

    clock.addAndGet(600L);
    assertTrue(tokens.redeem(viewer1, multiUse).isEmpty());
    assertEquals(0, tokens.activeTokenCount());

    String t1 = tokens.issue(viewer1, action1, false);
    String t2 = tokens.issue(viewer1, action2, false);
    String t3 = tokens.issue(viewer1, action3, false);
    assertEquals(2, tokens.activeTokenCount());
    assertTrue(tokens.redeem(viewer1, t1).isEmpty());
    assertEquals(Optional.of(action2), tokens.redeem(viewer1, t2));
    assertEquals(Optional.of(action3), tokens.redeem(viewer1, t3));

    tokens.invalidateViewer(viewer1);
    assertEquals(0, tokens.activeTokenCount());

    tokens.issue(viewer2, action1);
    tokens.invalidateAll();
    assertEquals(0, tokens.activeTokenCount());
  }

  @Test
  @DisplayName("MenuRenderer, AnvilInputOpener, and MenuBindingSupport handle discovery and errors")
  void menuBindingSupportDiscoveryAndResilience() {
    UUID viewer = UUID.randomUUID();
    MenuDescriptor descriptor =
        new MenuDescriptor(
            "Title",
            List.of(
                new MenuDescriptor.Page(
                    List.of(MenuDescriptor.Line.of(MenuDescriptor.Fragment.plain("hello"))))));

    AtomicReference<MenuDescriptor> rendered = new AtomicReference<>();
    MenuRenderer bukkitBookRenderer = (v, d) -> rendered.set(d);
    bukkitBookRenderer.render(viewer, descriptor);
    assertSame(descriptor, rendered.get());

    MenuRenderer fabricChatRenderer = (v, d) -> {};

    MenuRendererProvider brokenProvider =
        new MenuRendererProvider() {
          @Override
          public String id() {
            return "book";
          }

          @Override
          public MenuRenderer create() {
            throw new NoClassDefFoundError("simulated missing platform class");
          }

          @Override
          public PlatformFamily platformFamily() {
            return PlatformFamily.BUKKIT;
          }
        };

    MenuRendererProvider bukkitProvider =
        new MenuRendererProvider() {
          @Override
          public String id() {
            return "book";
          }

          @Override
          public MenuRenderer create() {
            return bukkitBookRenderer;
          }

          @Override
          public PlatformFamily platformFamily() {
            return PlatformFamily.BUKKIT;
          }
        };

    MenuRendererProvider fabricProvider =
        new MenuRendererProvider() {
          @Override
          public String id() {
            return "chat";
          }

          @Override
          public MenuRenderer create() {
            return fabricChatRenderer;
          }

          @Override
          public PlatformFamily platformFamily() {
            return PlatformFamily.FABRIC;
          }
        };

    MenuRenderer resolvedBukkit =
        MenuBindingSupport.discoverRenderer(
            List.of(brokenProvider, bukkitProvider, fabricProvider),
            List.of("book", "chat"),
            PlatformFamily.BUKKIT);
    assertSame(bukkitBookRenderer, resolvedBukkit);

    MenuRenderer resolvedFabric =
        MenuBindingSupport.discoverRenderer(
            List.of(bukkitProvider, fabricProvider),
            List.of("book", "chat"),
            PlatformFamily.FABRIC);
    assertSame(fabricChatRenderer, resolvedFabric);

    AtomicReference<String> openedParam = new AtomicReference<>();
    AnvilInputOpener opener =
        (v, path, param, prefill) -> {
          openedParam.set(param + "=" + prefill);
          return true;
        };
    opener.setStagingSink((v, s, p, val) -> {});
    assertTrue(
        opener.open(
            viewer, List.of("rtp"), "max", "500", MenuAction.Mode.STAGE, (v, s, p, val) -> {}));
    assertEquals("max=500", openedParam.get());

    AnvilInputOpenerProvider failingOpenerProvider =
        new AnvilInputOpenerProvider() {
          @Override
          public AnvilInputOpener create() {
            throw new IllegalStateException("boom");
          }

          @Override
          public PlatformFamily platformFamily() {
            return PlatformFamily.BUKKIT;
          }
        };
    AnvilInputOpenerProvider validOpenerProvider = () -> opener;
    assertNull(validOpenerProvider.platformFamily());

    AnvilInputOpener resolvedOpener =
        MenuBindingSupport.discoverAnvilOpener(
            List.of(failingOpenerProvider, validOpenerProvider), PlatformFamily.BUKKIT);
    assertSame(opener, resolvedOpener);

    AtomicInteger step = new AtomicInteger();
    Iterator<String> flakyIterator =
        new Iterator<>() {
          @Override
          public boolean hasNext() {
            int s = step.incrementAndGet();
            if (s == 2) {
              throw new ServiceConfigurationError("bad provider config");
            }
            return s <= 4;
          }

          @Override
          public String next() {
            if (step.get() == 3) {
              throw new LinkageError("incompatible class");
            }
            return "provider-" + step.get();
          }
        };
    List<String> loaded = MenuBindingSupport.loadIteratorSafely(flakyIterator, "test provider");
    assertEquals(List.of("provider-1", "provider-4"), loaded);

    assertNull(MenuBindingSupport.currentPlatformFamily());
    ServerAccessors.set(
        new ServerAccessor() {
          @Override
          public String getServerVersion() {
            return "1.21.1";
          }

          @Override
          public String getPlatform() {
            return "neoforge";
          }

          @Override
          public Integer getServerIntVersion() {
            return 21;
          }

          @Override
          public void log(Level level, String message) {}

          @Override
          public void log(Level level, String message, Throwable throwable) {}
        });
    assertEquals(PlatformFamily.NEOFORGE, MenuBindingSupport.currentPlatformFamily());
    assertTrue(MenuBindingSupport.matchesPlatform(null, PlatformFamily.NEOFORGE));
    assertTrue(MenuBindingSupport.matchesPlatform(PlatformFamily.NEOFORGE, PlatformFamily.UNKNOWN));
    assertTrue(MenuBindingSupport.matchesPlatform(PlatformFamily.NEOFORGE, null));
    assertTrue(
        MenuBindingSupport.matchesPlatform(PlatformFamily.NEOFORGE, PlatformFamily.NEOFORGE));
    assertFalse(MenuBindingSupport.matchesPlatform(PlatformFamily.BUKKIT, PlatformFamily.NEOFORGE));

    assertNull(MenuBindingSupport.discoverRenderer());
    assertNull(MenuBindingSupport.discoverAnvilOpener());
    assertNull(
        MenuBindingSupport.discoverRenderer(
            Arrays.asList((MenuRendererProvider) null), List.of(" ", "book"), null));
  }
}
