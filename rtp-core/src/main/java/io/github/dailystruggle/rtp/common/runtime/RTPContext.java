package io.github.dailystruggle.rtp.common.runtime;

import io.github.dailystruggle.rtp.api.economy.RTPEconomy;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;

import java.util.logging.Level;

/**
 * Platform services every core package may reach (ADR-115 Tier 1). Typed only by
 * {@code rtp-api} contracts so this package depends on no other {@code rtp-core} package.
 *
 * <p>Each accessor returns {@code null} until the platform installs the service; callers keep
 * the null checks they had against the former {@code RTP} statics.
 */
public interface RTPContext {
  /** Platform accessor, or {@code null} before platform enable. */
  RTPServerAccessor serverAccessor();

  /** Active scheduler, or {@code null} before platform enable. */
  RTPScheduler scheduler();

  /** Economy binding, or {@code null} when none is installed. */
  RTPEconomy economy();

  void log(Level level, String msg);

  void log(Level level, String msg, Throwable t);
}
