package io.github.dailystruggle.rtp.common.factory;

import java.util.EnumMap;

/**
 * Owner of the process-wide factory map, keyed by {@link FactoryNames} (ADR-115 seam 4).
 *
 * <p>The map starts empty; the composition root ({@code common.RTP}) populates it during its
 * static initialization, before any platform installs a server accessor. The map is the
 * mutable instance the former {@code FactoryRegistry.map()} static exposed, so tests and addons may
 * still replace an entry. It is not synchronized: writes happen during bootstrap or in tests.
 */
public final class FactoryRegistry {
  private static final EnumMap<FactoryNames, Factory<?>> MAP = new EnumMap<>(FactoryNames.class);

  private FactoryRegistry() {
  }

  /** The live, mutable factory map. */
  public static EnumMap<FactoryNames, Factory<?>> map() {
    return MAP;
  }

  /** The factory registered under {@code name}, or {@code null} if none is registered. */
  public static Factory<?> get(FactoryNames name) {
    return MAP.get(name);
  }
}
