package br.net.fabiozumbi12.RedProtect.Bukkit;

import br.net.fabiozumbi12.RedProtect.Bukkit.API.RedProtectAPI;

/** Test stand-in of the RedProtect 7.7 main class: static {@code get()} and {@code getAPI()}. */
public class RedProtect {
  private static RedProtect instance;
  private final RedProtectAPI api;

  public RedProtect(RedProtectAPI api) {
    this.api = api;
  }

  public static RedProtect get() {
    return instance;
  }

  public static void setInstance(RedProtect value) {
    instance = value;
  }

  public RedProtectAPI getAPI() {
    return api;
  }
}
