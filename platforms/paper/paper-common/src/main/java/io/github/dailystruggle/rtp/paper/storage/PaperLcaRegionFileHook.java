package io.github.dailystruggle.rtp.paper.storage;

import io.github.dailystruggle.anvil.LcaRegionFileStorage;
import io.github.dailystruggle.anvil.LcaRegionReader;
import io.github.dailystruggle.rtp.bukkitplatform.world.BukkitRegionPaths;
import org.bukkit.World;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Paper / Folia / Spigot world storage hook that binds `.lca`
 * ({@link LcaRegionFileStorage.LcaWorldStorageSession}) to Bukkit {@link World} dimension folders
 * (`region/`, `entities/`, `poi/`) and discovers NMS `RegionFileStorage` instances on the backing
 * `ServerLevel` (`anvil-api-ADR-003` Sections 2.1, 2.3, 4).
 *
 * @governedBy anvil-api-ADR-003
 * @governedBy anvil-api-ADR-002
 * @governedBy ADR-115
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
public final class PaperLcaRegionFileHook implements Closeable, AutoCloseable {

    private final Map<UUID, LcaRegionFileStorage.LcaWorldStorageSession> worldSessions = new ConcurrentHashMap<>();
    private final boolean modeBZstd;
    private final boolean autoMigrateLegacy;
    private final int defaultDataVersion;

    public PaperLcaRegionFileHook() {
        this(true, true, 4671);
    }

    public PaperLcaRegionFileHook(boolean modeBZstd, boolean autoMigrateLegacy, int defaultDataVersion) {
        this.modeBZstd = modeBZstd;
        this.autoMigrateLegacy = autoMigrateLegacy;
        this.defaultDataVersion = defaultDataVersion;
        LcaRegionReader.register();
    }

    /**
     * Attaches (or returns the already-attached) `.lca` {@link LcaRegionFileStorage.LcaWorldStorageSession}
     * for `world`, resolving its dimension root (`DIM-1` for Nether, `DIM1` for The End) and vertical
     * section range (`world.getMinHeight() .. world.getMaxHeight()`).
     */
    public LcaRegionFileStorage.LcaWorldStorageSession attachWorld(World world) {
        if (world == null) {
            throw new IllegalArgumentException("world must not be null");
        }
        return worldSessions.computeIfAbsent(world.getUID(), uuid -> {
            Path dimDir = resolveDimensionDirectory(world);
            int minSectionY = world.getMinHeight() >> 4;
            int maxSectionY = (world.getMaxHeight() - 1) >> 4;
            int sectionCount = Math.max(1, maxSectionY - minSectionY + 1);
            return new LcaRegionFileStorage.LcaWorldStorageSession(
                    dimDir,
                    minSectionY,
                    sectionCount,
                    defaultDataVersion,
                    modeBZstd,
                    autoMigrateLegacy);
        });
    }

    public LcaRegionFileStorage.LcaWorldStorageSession getSession(World world) {
        return world == null ? null : worldSessions.get(world.getUID());
    }

    public LcaRegionFileStorage.LcaWorldStorageSession getSession(UUID worldId) {
        return worldId == null ? null : worldSessions.get(worldId);
    }

    public int attachedWorldCount() {
        return worldSessions.size();
    }

    /**
     * Resolves the on-disk dimension directory containing `region/`, `entities/`, and `poi/` for `world`.
     */
    public static Path resolveDimensionDirectory(World world) {
        File worldFolder = world.getWorldFolder();
        Path base = worldFolder.toPath();
        String subpath = BukkitRegionPaths.dimensionRegionSubpath(world);
        if (subpath != null && !subpath.isEmpty()) {
            return base.resolve(subpath);
        }
        World.Environment env = world.getEnvironment();
        if (env == World.Environment.NETHER) {
            Path dimNether = base.resolve("DIM-1");
            if (Files.isDirectory(dimNether) || !Files.isDirectory(base.resolve("region"))) {
                return dimNether;
            }
        } else if (env == World.Environment.THE_END) {
            Path dimEnd = base.resolve("DIM1");
            if (Files.isDirectory(dimEnd) || !Files.isDirectory(base.resolve("region"))) {
                return dimEnd;
            }
        }
        return base;
    }

    /**
     * Reflectively inspects a `CraftWorld` (or NMS `ServerLevel` / `ChunkMap`) to locate any active
     * NMS `RegionFileStorage` instances without requiring compile-time `net.minecraft.*` imports.
     */
    public static List<Object> discoverNmsRegionFileStorages(Object craftWorldOrServerLevel) {
        if (craftWorldOrServerLevel == null) {
            return Collections.emptyList();
        }
        List<Object> found = new ArrayList<>(4);
        try {
            Object serverLevel = craftWorldOrServerLevel;
            try {
                Method getHandle = craftWorldOrServerLevel.getClass().getMethod("getHandle");
                Object handle = getHandle.invoke(craftWorldOrServerLevel);
                if (handle != null) {
                    serverLevel = handle;
                }
            } catch (NoSuchMethodException ignored) {
                // Already an NMS ServerLevel or custom test carrier
            }
            collectRegionFileStoragesReflectively(serverLevel, found, 0);
        } catch (ReflectiveOperationException ignored) {
            // Best-effort reflective discovery across Spigot/Paper/Folia/Moonrise mappings
        }
        return found;
    }

    private static void collectRegionFileStoragesReflectively(Object target, List<Object> out, int depth)
            throws IllegalAccessException {
        if (target == null || depth > 5) {
            return;
        }
        Class<?> cls = target.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                Class<?> ft = f.getType();
                String typeName = ft.getSimpleName();
                if (typeName.contains("RegionFileStorage")) {
                    f.setAccessible(true);
                    Object val = f.get(target);
                    if (val != null && !out.contains(val)) {
                        out.add(val);
                    }
                } else if (depth < 5 && (typeName.contains("ChunkMap")
                        || typeName.contains("ChunkSource")
                        || typeName.contains("ChunkCache")
                        || typeName.contains("EntitySectionManager")
                        || typeName.contains("EntityPersistentStorage")
                        || typeName.contains("EntityStorage")
                        || typeName.contains("PoiManager")
                        || typeName.contains("SectionStorage")
                        || typeName.contains("SimpleRegionStorage")
                        || typeName.contains("IOWorker")
                        || typeName.contains("RegionFileIO")
                        || typeName.contains("DataController")
                        || typeName.contains("ChunkTaskScheduler"))) {
                    f.setAccessible(true);
                    Object child = f.get(target);
                    if (child != null && child != target) {
                        collectRegionFileStoragesReflectively(child, out, depth + 1);
                    }
                }
            }
            cls = cls.getSuperclass();
        }
    }

    /**
     * Flushes all `.lca` storage folders (`region/`, `entities/`, `poi/`) for `world`.
     */
    public void flushWorld(World world) throws IOException {
        if (world == null) {
            return;
        }
        LcaRegionFileStorage.LcaWorldStorageSession session = worldSessions.get(world.getUID());
        if (session != null) {
            session.flush();
        }
    }

    /**
     * Flushes, compacts if fragmented, and closes the `.lca` storage session for `world`.
     */
    public void detachWorld(World world) throws IOException {
        if (world == null) {
            return;
        }
        LcaRegionFileStorage.LcaWorldStorageSession session = worldSessions.remove(world.getUID());
        if (session != null) {
            session.close();
        }
    }

    @Override
    public void close() throws IOException {
        IOException first = null;
        List<LcaRegionFileStorage.LcaWorldStorageSession> sessions = new ArrayList<>(worldSessions.values());
        worldSessions.clear();
        for (LcaRegionFileStorage.LcaWorldStorageSession session : sessions) {
            try {
                session.close();
            } catch (IOException e) {
                if (first == null) first = e;
            }
        }
        if (first != null) {
            throw first;
        }
    }
}
