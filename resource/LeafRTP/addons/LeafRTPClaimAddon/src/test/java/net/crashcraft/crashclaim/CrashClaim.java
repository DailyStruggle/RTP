package net.crashcraft.crashclaim;

import net.crashcraft.crashclaim.api.CrashClaimAPI;

/** Test stub of the CrashClaim main class: static {@code getPlugin()}, instance {@code getApi()}. */
public class CrashClaim {
  private static CrashClaim plugin;
  private CrashClaimAPI api;

  public static CrashClaim getPlugin() {
    return plugin;
  }

  public static void setPlugin(CrashClaim value) {
    plugin = value;
  }

  public CrashClaimAPI getApi() {
    return api;
  }

  public void setApi(CrashClaimAPI value) {
    api = value;
  }
}
