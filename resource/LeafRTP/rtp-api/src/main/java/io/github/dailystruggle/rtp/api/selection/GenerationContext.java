package io.github.dailystruggle.rtp.api.selection;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * Immutable context for async teleport location generation (REQ-API-ARCH-001).
 *
 * @see ILocationGenerator#getLocation(Object, GenerationContext)
 * @see ILocationGenerator#generateLocation(Object, GenerationContext)
 */
public final class GenerationContext {
    private final RTPCommandSender sender;
    private final RTPPlayer player;
    private final Set<String> biomeNames;

  /**
   * Creates a generation context.
   *
   * @param sender     initiating command sender; non-null
   * @param player     target player; non-null
   * @param biomeNames optional biome filter, or null/empty for none
   */
    public GenerationContext(RTPCommandSender sender, RTPPlayer player, @Nullable Set<String> biomeNames) {
        this.sender = sender;
        this.player = player;
        this.biomeNames = biomeNames;
    }

  /**
   * Returns the command sender who initiated the teleport request.
   *
   * @return the sender; never {@code null}
   */
    public RTPCommandSender sender() {
        return sender;
    }

  /**
   * Returns the player who will be teleported.
   *
   * @return the player; never {@code null}
   */
    public RTPPlayer player() {
        return player;
    }

  /**
   * Returns optional biome filter applied during candidate selection.
   *
   * @return canonical biome names, or null if unfiltered
   */
    public @Nullable Set<String> biomeNames() {
        return biomeNames;
    }
}
