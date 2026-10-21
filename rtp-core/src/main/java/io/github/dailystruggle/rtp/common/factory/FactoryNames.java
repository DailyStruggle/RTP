package io.github.dailystruggle.rtp.common.factory;

/**
 * Keys of the process-wide factories held by {@link FactoryRegistry}.
 *
 * <p>Constant names and order shall not change: they were {@code FactoryNames} and callers
 * iterate {@link #values()} in declaration order (ADR-115 invariant 3).
 */
public enum FactoryNames {
  shape,
  vert,
  singleConfig,
  multiConfig
}
