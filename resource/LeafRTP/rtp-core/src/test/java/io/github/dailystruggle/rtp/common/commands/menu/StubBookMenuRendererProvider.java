package io.github.dailystruggle.rtp.common.commands.menu;

import io.github.dailystruggle.rtp.api.menu.MenuModel;
import io.github.dailystruggle.rtp.api.menu.MenuRenderer;
import io.github.dailystruggle.rtp.api.menu.MenuRendererProvider;
import java.util.UUID;

/**
 * Test {@link MenuRendererProvider} registered through {@code META-INF/services}
 * on the rtp-core test classpath, so {@link MenuBindingSupport#discoverRenderer()}
 * can be exercised end-to-end without a platform adapter present. Supplies the
 * {@code "book"} id (the default {@code menu.renderer} selection).
 */
public final class StubBookMenuRendererProvider implements MenuRendererProvider {

  /** Identifiable renderer instance returned by {@link #create()}. */
  public static final class StubRenderer implements MenuRenderer {
    @Override
    public void render(UUID playerId, MenuModel model) {
      // no-op test stub
    }
  }

  @Override
  public String id() {
    return "book";
  }

  @Override
  public MenuRenderer create() {
    return new StubRenderer();
  }
}
