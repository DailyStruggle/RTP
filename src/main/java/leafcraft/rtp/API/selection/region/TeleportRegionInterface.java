package leafcraft.rtp.API.selection.region;

import leafcraft.rtp.API.selection.region.selectors.SelectorInterface;
import leafcraft.rtp.tools.selection.ChunkSet;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiFunction;

public interface TeleportRegionInterface {
    /**
     * @return this region's selector
     */
    SelectorInterface getSelector();

    /**
     * @param selector selector to give this region
     */
    void setSelector(SelectorInterface selector);

    /**
     * hasQueuedLocation(player): check whether the region has a location reserved for the player
     * @param uuid - who to check for
     * @return whether the region has a location reserved for the player
     */
    boolean hasQueuedLocation(UUID uuid);

    /**
     * hasQueuedLocation(): check whether the region has a location reserved for the player
     * @return whether the region has any location queued
     */
    boolean hasQueuedLocation();

    /**
     * queue length checking functions
     * @param uuid - who to check for
     * @return how many locations are ready
     */
    int getTotalQueueLength(UUID uuid);
    int getPlayerQueueLength(UUID uuid);
    int getPublicQueueLength();

    /**
     * getQueuedLocation - if a
     * @param sender - who to tell about a failure
     * @param player - who to tell about a failure
     * @return popped location, or null if no locations were ready
     */
    Location getQueuedLocation(CommandSender sender, Player player);

    /**
     * getLocation
     * @param sender - who to tell about a failure
     * @param player - who to tell about a failure
     * @param biome - what biome, or null
     * @return popped location, generated location, or null if too many tries
     */
    Location getLocation(CommandSender sender, Player player, Set<Biome> biome);
    Location getLocation(boolean urgent, CommandSender sender, Player player, Set<Biome> biome);
    Location getLocation(boolean urgent, CommandSender sender, Player player);

    /**
     * queueRandomLocation - attempt to select and queue a location
     */
    void queueRandomLocation();

    /**
     * queueRandomLocation - attempt to select and queue a location
     * @param uuid - who to reserve the location for
     */
    void queueRandomLocation(UUID uuid);

    /**
     * recyclePlayerLocations - dump any reserved locations back onto the public queue
     * @param uuid - whose locations to recycle
     */
    void recyclePlayerLocations(UUID uuid);

    /**
     *
     * @param biome - what biome to look for, or null for any biome
     * @return a corresponding location, or null on failure
     */
    Location getRandomLocation(@Nullable Set<Biome> biome);

    /**
     * @return queue of currently awaiting players
     */
    ConcurrentLinkedQueue<UUID> getPlayerQueue();

    /**
     * getChunks - after getting a location, get the mapped CompleteableFuture chunks and their completion status
     * @return ChunkSet that's mapped to the location, or null if none exists
     */
    @Nullable
    ChunkSet getChunks(Location location);

    void shutdown();
}
