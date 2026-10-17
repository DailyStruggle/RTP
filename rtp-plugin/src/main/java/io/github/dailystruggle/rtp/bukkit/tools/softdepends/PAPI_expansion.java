package io.github.dailystruggle.rtp.bukkit.tools.softdepends;

import io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

public class PAPI_expansion extends PlaceholderExpansion {
  /** Registering plugin (full or lite entry point); the expansion's author comes from its descriptor. */
  private final Plugin owner;

  public PAPI_expansion(Plugin owner) {
    this.owner = owner;
  }

  @Override
  public boolean persist() {
    return true;
  }

  @Override
  public boolean canRegister() {
    return true;
  }

  @Override
  public @NotNull String getAuthor() {
    return owner.getDescription().getAuthors().toString();
  }

  @Override
  public @NotNull String getIdentifier() {
    return "rtp";
  }

  @Override
  public @NotNull String getVersion() {
    return "${version}";
  }

  @Override
  public String onRequest(OfflinePlayer player, @NotNull String params) {
    return onPlaceholderRequest(player.getPlayer(), params);
  }

  @Override
  public String onPlaceholderRequest(Player player, @NotNull String identifier) {
    if (player == null) {
      return "";
    }

    if (SendMessage.placeholders.containsKey(identifier))
      return SendMessage.placeholders.get(identifier).apply(player.getUniqueId());

    return "";
  }
}
