package leafcraft.rtp.API.selection;

import leafcraft.rtp.tools.selection.ChunkSet;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Biome;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public interface TeleportRegion {
    /**
     * hasQueuedLocation(player): check whether the region has a location reserved for the player
     * @param player
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

    int getTotalQueueLength(OfflinePlayer player);
    int getPlayerQueueLength(OfflinePlayer player);
    int getPlayerQueueLength(Player player);
    int getPlayerQueueLength(UUID uuid);
    int getPublicQueueLength();

    Location getQueuedLocation(CommandSender sender, Player player);

    Location getLocation(boolean urgent, CommandSender sender, Player player, Biome biome);
    Location getLocation(boolean urgent, CommandSender sender, Player player);

    void queueRandomLocation();

    void queueRandomLocation(OfflinePlayer player);

    void queueRandomLocation(Player player);

    void queueRandomLocation(UUID uuid);

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
     * @param urgent - whether to call getChunkAtAsyncUrgently instead of getChunkAtAsync
     * @param biome - what biome to look for, or null for any biome
     * @return a corresponding location, or null on failure
     */
    Location getRandomLocation(boolean urgent, @Nullable Biome biome);
    Location getRandomLocation(boolean urgent);

    /**
     * getChunks - after getting a location, get the mapped CompleteableFuture chunks and their completion status
     * @param location
     * @return ChunkSet that's mapped to the location, or null if none exists
     */
    @Nullable
    ChunkSet getChunks(Location location);

    int getFirstNonAir(ChunkSnapshot chunk);
    int getLastNonAir(ChunkSnapshot chunk, int start);

    boolean isKnownBad(int x, int z);
    boolean isKnownBad(long location);

    boolean isInBounds(int x, int z);
    boolean isInBounds(long location);

    boolean checkLocation(ChunkSnapshot chunkSnapshot, int y);

    void loadFile();
    void storeFile();

    void shutdown();
}
