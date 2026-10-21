package io.github.dailystruggle.rtp.common.network;

import io.github.dailystruggle.rtp.api.network.NetworkCommandHook;
import io.github.dailystruggle.rtp.common.network.pluginmessage.NetworkBridge;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Process-wide network mode hooks (ADR-036; ADR-115 seam 4).
 *
 * <p>Replaces the former {@code NetworkHooks.commandHook()}, {@code NetworkHooks.bridgeFactory()},
 * {@code NetworkHooks.backendStateSamplerFactory()} and {@code NetworkHooks.lobbyMode()} statics with the same
 * defaults: {@link NetworkCommandHook#LOCAL_ONLY}, {@code null}, {@code null} and
 * {@code false}. Every slot is {@code volatile}; platforms install factories during startup.
 */
public final class NetworkHooks {
  private static volatile NetworkCommandHook commandHook = NetworkCommandHook.LOCAL_ONLY;
  private static volatile Supplier<NetworkBridge> bridgeFactory;
  private static volatile Function<Boolean, BackendStateSampler> backendStateSamplerFactory;
  private static volatile boolean lobbyMode = false;

  private NetworkHooks() {
  }

  /** Decides whether {@code /rtp} is served locally, queued cross-server, or rejected. */
  public static NetworkCommandHook commandHook() {
    return commandHook;
  }

  public static void setCommandHook(NetworkCommandHook hook) {
    commandHook = hook;
  }

  /** Platform factory for the plugin-message bridge, or {@code null} when unsupported. */
  public static Supplier<NetworkBridge> bridgeFactory() {
    return bridgeFactory;
  }

  public static void setBridgeFactory(Supplier<NetworkBridge> factory) {
    bridgeFactory = factory;
  }

  /**
   * Platform factory for the backend heartbeat sampler; the argument is the lobby-mode flag.
   * {@code null} when no sampler is installed.
   */
  public static Function<Boolean, BackendStateSampler> backendStateSamplerFactory() {
    return backendStateSamplerFactory;
  }

  public static void setBackendStateSamplerFactory(Function<Boolean, BackendStateSampler> factory) {
    backendStateSamplerFactory = factory;
  }

  /** True when this backend is a pure cross-server lobby (no local region pre-fill). */
  public static boolean lobbyMode() {
    return lobbyMode;
  }

  public static void setLobbyMode(boolean value) {
    lobbyMode = value;
  }
}
