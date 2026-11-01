package io.github.dailystruggle.rtp.paperplatform.server;

import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.bukkitplatform.server.AbstractServerAccessor;
import io.github.dailystruggle.rtp.paperplatform.entity.PaperRTPPlayer;
import org.bukkit.entity.Player;

/**
 * Paper-family base server accessor. Wraps players in {@link PaperRTPPlayer} so the view-distance
 * APIs (including the per-player send-view-distance pin used by ADR-072) are called directly through
 * paper-api rather than reflectively. Version-specific accessors extend this for world/biome wiring.
 */
public abstract class PaperServerAccessor extends AbstractServerAccessor {
  @Override
  protected RTPPlayer wrapPlayer(Player player) {
    return new PaperRTPPlayer(player);
  }
}
