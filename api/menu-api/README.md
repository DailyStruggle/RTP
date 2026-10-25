# menu-api

Cross-platform declarative menu, navigation session, expiring click-token, and anvil text-prompt SPI (`io.github.dailystruggle.menu.api`). Pure Java 17 layered strictly on `:server-api` and `:commands-api` with zero platform or `rtp-core` dependencies, allowing any standalone plugin or mod (e.g. `OnePlayerSleep`, `WorldGuardLite`) to author interactive menus once and render them as written books, chat views, or chest inventories across Spigot, Paper, Folia, Fabric, and NeoForge.

## Coordinates

- **Maven Central:** `io.github.dailystruggle:menu-api:<version>`
- **JitPack:** `com.github.DailyStruggle.RTP:menu-api:<tag>`

```gradle
dependencies {
    implementation 'io.github.dailystruggle:menu-api:3.3.1'
    // or via JitPack:
    // implementation 'com.github.DailyStruggle.RTP:menu-api:3.3.1'
}
```

## Surface

- `MenuDescriptor` - immutable multi-page menu model (`title`, `rows`, `List<Page>`, `styleMetadata`) built via `MenuDescriptor.builder(title)`. Each `Page` holds `Line`s of `Fragment(text, hover, action)` (for book/chat renderers) and/or slot-addressed `Item(slot, label, lore, iconKey, action)` entries (for chest GUI renderers).
- `MenuAction` - sealed click/prompt action hierarchy: `RunCommand`, `OpenSubmenu`, `ChangePage`, `SuggestInput`, `PromptAnvilInput` (with `Mode.RUN` or `Mode.STAGE`), `OpenExternalUri` (`https` only), `StageValue`, `UnstageValue`, `ApplyStagedValues`, and `DiscardStagedValues`.
- `MenuItemClick` - immutable click interaction record `(UUID viewer, int pageIndex, int slot, ClickType clickType, MenuAction action)` with `ofSlot(...)` and `ofFragment(...)` factories.
- `MenuSession` - bounded per-viewer navigation stack (`push`, `pop`, `current`, `setCurrentPageIndex`, `canGoBack`) and scoped key-value staging cart (`stage`, `unstage`, `stagedValues`, `hasStagedChanges`, `clearStaged`).
- `MenuRenderer` & `MenuRendererProvider` - functional rendering seam (`render(UUID viewer, MenuDescriptor descriptor)`) and `ServiceLoader` provider interface (`id()`, `create()`, `platformFamily()`).
- `MenuTokenService` - bounded (`DEFAULT_MAX_TOKENS = 256`), expiring (`DEFAULT_TTL = 5m`) `SecureRandom` hex token issuer and redeemer (`issue(viewer, action)`, `redeem(viewer, token)`, `invalidateViewer(viewer)`) for server-side callback verification.
- `AnvilInputOpener` & `AnvilInputOpenerProvider` - platform-neutral SPI (`open(viewer, parentPath, paramName, prefill, mode, sink)`) for opening anvil or text-input prompts in `RUN` or `STAGE` mode.
- `MenuBindingSupport` - `LinkageError`-tolerant `ServiceLoader` discovery (`discoverRenderer(preferredIds)`, `discoverAnvilOpener()`, `loadProvidersSafely(...)`) gated by `ServerAccessors.get().getPlatformFamily()`.

## Usage

```java
import io.github.dailystruggle.menu.api.MenuAction;
import io.github.dailystruggle.menu.api.MenuBindingSupport;
import io.github.dailystruggle.menu.api.MenuDescriptor;
import io.github.dailystruggle.menu.api.MenuRenderer;
import io.github.dailystruggle.menu.api.MenuSession;
import io.github.dailystruggle.menu.api.MenuTokenService;
import java.util.List;
import java.util.UUID;

// Standalone plugin (e.g. OnePlayerSleep) building a cross-platform settings menu:
MenuDescriptor descriptor = MenuDescriptor.builder("OnePlayerSleep Settings")
        .addPage(new MenuDescriptor.Page(
                List.of(MenuDescriptor.Line.of(new MenuDescriptor.Fragment(
                        "[Set Sleep Percentage]",
                        "Click to enter a percentage (0-100)",
                        new MenuAction.PromptAnvilInput(
                                List.of("ops", "config"), "sleepPercent", "50", MenuAction.Mode.STAGE)))),
                List.of(new MenuDescriptor.Item(
                        13, "Apply Changes", List.of("Commit staged settings"), "EMERALD",
                        new MenuAction.ApplyStagedValues("config.yml")))))
        .build();

UUID viewerId = UUID.randomUUID();
MenuSession session = new MenuSession(viewerId);
session.push(descriptor, 0);

MenuTokenService tokens = new MenuTokenService();
String clickToken = tokens.issue(viewerId, new MenuAction.ApplyStagedValues("config.yml"));

MenuRenderer renderer = MenuBindingSupport.discoverRenderer(List.of("book", "chat"));
if (renderer != null) {
    renderer.render(viewerId, descriptor);
}
```

## Build

```
./gradlew :menu-api:build
```

## Docs

- ADR-035: [`../../docs/adr/ADR-035-interactive-menus-book-first.md`](../../docs/adr/ADR-035-interactive-menus-book-first.md)
- ADR-044: [`../../docs/adr/ADR-044-command-tree-menu-reflector.md`](../../docs/adr/ADR-044-command-tree-menu-reflector.md)
- ADR-048: [`../../docs/adr/ADR-048-menu-builders-behind-server-accessor.md`](../../docs/adr/ADR-048-menu-builders-behind-server-accessor.md)
