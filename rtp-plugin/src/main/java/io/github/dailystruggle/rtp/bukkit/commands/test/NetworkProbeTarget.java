package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.commands.base.BaseRTPCmdImpl;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Parameter vocabulary and dispatch entry the {@code rtp test network} probe subcommands share.
 * The probe leaves depend on this type rather than on {@link NetworkSimulationTestJob}, which
 * registers them, so the parent and its leaves do not form a class cycle.
 */
public abstract class NetworkProbeTarget extends BaseRTPCmdImpl {

  /** Default number of synthetic peers when invoked without an argument. */
  static final int DEFAULT_PEER_COUNT = 3;

  /** Default observation window in ms - sleep between publish and assert. */
  static final long DEFAULT_OBSERVE_MS = 500L;

  /** Hard upper bound on peer count to keep the probe lightweight. */
  static final int MAX_PEER_COUNT = 16;

  /** Default number of synthetic reservation tokens when invoked without an argument. */
  static final int DEFAULT_TOKEN_COUNT = 2;

  /** Hard upper bound on the token-probe peer count to keep the probe lightweight. */
  static final int MAX_TOKEN_COUNT = 8;

  /** Default reservation TTL for the token probe (ms). */
  static final long DEFAULT_TOKEN_TTL_MS = 2_000L;

  /** Parameter name for the peer / token count (positional N replacement). */
  public static final String PARAM_COUNT = "count";

  /** Parameter name for the heartbeat observe window in ms. */
  public static final String PARAM_OBSERVE_MS = "observeMs";

  /** Parameter name for the per-token TTL in ms (tokens probe). */
  public static final String PARAM_TTL_MS = "ttlMs";

  /** Which probe a subcommand selected. Used only inside {@link #dispatchProbe}. */
  enum ProbeKind { HEARTBEAT, TOKENS, ALL }

  protected NetworkProbeTarget(@Nullable CommandsAPICommand parent) {
    super(parent);
  }

  /**
   * Runs the selected probe; every outcome (including NOT-CONFIGURED when network mode is off,
   * REQ-RTP-NET-002) is reported as an audit row, never dropped (S-004).
   */
  abstract void dispatchProbe(UUID callerId, ProbeKind kind,
          int peerCount, long observeWindowMs, long tokenTtlMs);

  static int clamp(int v, int lo, int hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  static long clamp(long v, long lo, long hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  static int parseInt(Map<String, List<String>> values, String key, int fallback) {
    if (values == null) return fallback;
    List<String> vs = values.get(key);
    if (vs == null || vs.isEmpty()) return fallback;
    try { return Integer.parseInt(vs.get(0)); }
    catch (NumberFormatException e) { return fallback; }
  }

  static long parseLong(Map<String, List<String>> values, String key, long fallback) {
    if (values == null) return fallback;
    List<String> vs = values.get(key);
    if (vs == null || vs.isEmpty()) return fallback;
    try { return Long.parseLong(vs.get(0)); }
    catch (NumberFormatException e) { return fallback; }
  }
}
