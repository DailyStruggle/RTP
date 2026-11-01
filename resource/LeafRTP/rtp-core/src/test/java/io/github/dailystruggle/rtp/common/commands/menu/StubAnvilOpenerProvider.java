package io.github.dailystruggle.rtp.common.commands.menu;

import java.util.List;
import java.util.UUID;

/**
 * Test {@link AnvilInputOpenerProvider} registered through
 * {@code META-INF/services} on the rtp-core test classpath, so
 * {@link MenuBindingSupport#discoverAnvilOpener()} can be exercised end-to-end
 * without a platform adapter present.
 */
public final class StubAnvilOpenerProvider implements AnvilInputOpenerProvider {

  /** Identifiable opener instance returned by {@link #create()}. */
  public static final class StubOpener implements MenuRedeemSubcommand.AnvilInputOpener {
    @Override
    public boolean open(UUID viewer, List<String> parentPath, String paramName, String prefill) {
      return false;
    }
  }

  @Override
  public MenuRedeemSubcommand.AnvilInputOpener create() {
    return new StubOpener();
  }
}
