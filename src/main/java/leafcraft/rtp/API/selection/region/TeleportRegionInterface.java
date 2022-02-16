package leafcraft.rtp.API.selection.region;

import leafcraft.rtp.API.selection.SelectionAPI;
import leafcraft.rtp.tools.selection.ChunkSet;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Biome;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

public interface TeleportRegionInterface {
    /**
     * hasQueuedLocation(player): check whether the region has a location reserved for the player
     * @param player/uuid - who to check for
     * @return whether the region has a location reserved for the player
     */
    boolean hasQueuedLocation(OfflinePlayer player);
    boolean hasQueuedLocation(Player player);
    boolean hasQueuedLocation(UUID uuid);

    /**
     * hasQueuedLocation(): check whether the region has a location reserved for the player
     * @return whether the region has any location queued
     */
    boolean hasQueuedLocation();

    /**
     * queue length checking functions
     * @param player/uuid - who to check for
     * @return how many locations are ready
     */
    int getTotalQueueLength(OfflinePlayer player);
    int getPlayerQueueLength(OfflinePlayer player);
    int getPlayerQueueLength(Player player);
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
     * @param urgent - whether to use getChunkAtAsyncUrgently instead of getChunkAtAsync
     * @param sender - who to tell about a failure
     * @param player - who to tell about a failure
     * @param biome - what biome, or null
     * @return popped location, generated location, or null if too many tries
     */
    Location getLocation(SelectionAPI.SyncState urgent, CommandSender sender, Player player, Biome biome);
    Location getLocation(boolean urgent, CommandSender sender, Player player, Biome biome);
    Location getLocation(boolean urgent, CommandSender sender, Player player);

    /**
     * queueRandomLocation - attempt to select and queue a location
     */
    void queueRandomLocation();

    /**
     * queueRandomLocation - attempt to select and queue a location
     * @param player/uuid - who to reserve the location for
     */
    void queueRandomLocation(OfflinePlayer player);
    void queueRandomLocation(Player player);
    void queueRandomLocation(UUID uuid);

    /**
     * recyclePlayerLocations - dump any reserved locations back onto the public queue
     * @param player/uuid - whose locations to recycle
     */
    void recyclePlayerLocations(OfflinePlayer player);
    void recyclePlayerLocations(Player player);
    void recyclePlayerLocations(UUID uuid);

    void addBadLocation(int chunkX, int chunkZ);
    void addBadLocation(Long location);

    void addBiomeLocation(int chunkX, int chunkZ, Biome biome);
    void addBiomeLocation(Long location, Biome biome);

    void removeBiomeLocation(int chunkX, int chunkZ, Biome biome);
    void removeBiomeLocation(Long location, Biome biome);

    /**
     *
     * @param state/urgent - whether to call getChunkAtAsyncUrgently, getChunkAtAsync, or world.getChunk
     * @param biome - what biome to look for, or null for any biome
     * @return a corresponding location, or null on failure
     */
    Location getRandomLocation(SelectionAPI.SyncState state, @Nullable Biome biome);
    Location getRandomLocation(boolean urgent, @Nullable Biome biome);
    Location getRandomLocation(boolean urgent);

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

    int getFirstNonAir(Chunk chunk);
    int getLastNonAir(Chunk chunk, int start);

    boolean isKnownBad(int x, int z);
    boolean isKnownBad(long location);

    boolean isInBounds(int x, int z);
    boolean isInBounds(long location);

    boolean checkLocation(Chunk chunkSnapshot, int y);

    void loadFile();
    void storeFile();

    void shutdown();
}
