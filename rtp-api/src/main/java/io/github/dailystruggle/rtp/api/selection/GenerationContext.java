package io.github.dailystruggle.rtp.api.selection;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

/**
 * Immutable snapshot of the context in which a teleport location is being generated.
 *
 * <p>Passed to {@link ILocationGenerator} at the point of request and threaded
 * through the async generation pipeline. Because generation runs off the main
 * server thread (REQ-API-ARCH-001), all fields must be safe to read from any
 * thread without synchronization — hence this class is {@code final} with only
 * final fields.
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
   * @param sender     the command sender who initiated the teleport request
   *                   (may be the player themselves, a console, or an admin);
   *                   must not be {@code null}
   * @param player     the player who will be teleported; must not be {@code null}
   * @param biomeNames optional set of biome names to restrict candidate selection;
   *                   {@code null} or empty means no biome filter is applied
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
   * Returns the optional biome filter applied during candidate selection.
   *
   * <p>When non-null and non-empty, only locations whose biome name is contained
   * in this set will be returned. Biome names use the platform's canonical format
   * (e.g. {@code "minecraft:forest"}).
   *
   * @return the biome name filter, or {@code null} if no filter is applied
   */
    public @Nullable Set<String> biomeNames() {
        return biomeNames;
    }
}
