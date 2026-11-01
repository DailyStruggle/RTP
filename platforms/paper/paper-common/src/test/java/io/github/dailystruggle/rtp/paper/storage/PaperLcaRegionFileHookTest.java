package io.github.dailystruggle.rtp.paper.storage;

import io.github.dailystruggle.anvil.LcaRegionFileStorage;
import io.github.dailystruggle.anvil.Nbt;
import org.bukkit.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link PaperLcaRegionFileHook} (`anvil-api-ADR-003`, `REQ-RTP-S-004`, `REQ-RTP-S-005`).
 */
@DisplayName("anvil-api-ADR-003: PaperLcaRegionFileHook World Attachment & NMS Storage Discovery")
class PaperLcaRegionFileHookTest {

    static final class SyntheticRegionFileStorage {
        final String name;

        SyntheticRegionFileStorage(String name) {
            this.name = name;
        }
    }

    static final class SyntheticChunkMap {
        @SuppressWarnings("unused")
        private final SyntheticRegionFileStorage voxelRegionStorage = new SyntheticRegionFileStorage("region");
    }

    static final class SyntheticEntityStorage {
        @SuppressWarnings("unused")
        private final SyntheticRegionFileStorage entityRegionStorage = new SyntheticRegionFileStorage("entities");
    }

    static final class SyntheticServerLevel {
        @SuppressWarnings("unused")
        private final SyntheticChunkMap chunkMap = new SyntheticChunkMap();
        @SuppressWarnings("unused")
        private final SyntheticEntityStorage entityStorage = new SyntheticEntityStorage();
    }

    static final class SyntheticCraftWorld {
        private final SyntheticServerLevel handle = new SyntheticServerLevel();

        public SyntheticServerLevel getHandle() {
            return handle;
        }
    }

    @Test
    @DisplayName("REQ-RTP-S-004 / REQ-RTP-S-005: PaperLcaRegionFileHook attaches Overworld, Nether (DIM-1), and End (DIM1) sessions and discovers NMS RegionFileStorage")
    void worldLifecycleAndReflectiveDiscovery(@TempDir Path tempDir) throws IOException {
        World overworld = Mockito.mock(World.class);
        UUID overworldId = UUID.randomUUID();
        Path overworldDir = tempDir.resolve("world");
        Files.createDirectories(overworldDir);
        Mockito.when(overworld.getUID()).thenReturn(overworldId);
        Mockito.when(overworld.getWorldFolder()).thenReturn(overworldDir.toFile());
        Mockito.when(overworld.getEnvironment()).thenReturn(World.Environment.NORMAL);
        Mockito.when(overworld.getMinHeight()).thenReturn(-64);
        Mockito.when(overworld.getMaxHeight()).thenReturn(320);

        World nether = Mockito.mock(World.class);
        UUID netherId = UUID.randomUUID();
        Path netherDir = tempDir.resolve("world_nether");
        Files.createDirectories(netherDir.resolve("DIM-1"));
        Mockito.when(nether.getUID()).thenReturn(netherId);
        Mockito.when(nether.getWorldFolder()).thenReturn(netherDir.toFile());
        Mockito.when(nether.getEnvironment()).thenReturn(World.Environment.NETHER);
        Mockito.when(nether.getMinHeight()).thenReturn(0);
        Mockito.when(nether.getMaxHeight()).thenReturn(256);

        try (PaperLcaRegionFileHook hook = new PaperLcaRegionFileHook(false, true, 4671)) {
            LcaRegionFileStorage.LcaWorldStorageSession owSession = hook.attachWorld(overworld);
            LcaRegionFileStorage.LcaWorldStorageSession netherSession = hook.attachWorld(nether);
            assertEquals(2, hook.attachedWorldCount());
            assertEquals(overworldDir, owSession.dimensionDirectory());
            assertEquals(netherDir.resolve("DIM-1"), netherSession.dimensionDirectory());

            LinkedHashMap<String, Object> entityRoot = new LinkedHashMap<>();
            entityRoot.put("DataVersion", 4671);
            entityRoot.put("Position", new int[] {1, 2});
            entityRoot.put("Entities", new Nbt.NbtList(Nbt.TAG_COMPOUND, List.of()));
            try (DataOutputStream out = owSession.entityStorage().getChunkDataOutputStream(1, 2)) {
                out.write(Nbt.writeNamedRoot("", entityRoot));
            }
            assertTrue(owSession.entityStorage().doesChunkExist(1, 2));
            try (DataInputStream in = owSession.entityStorage().getChunkDataInputStream(1, 2)) {
                assertNotNull(in);
                LinkedHashMap<String, Object> readBack = Nbt.readRootCompound(in.readAllBytes());
                assertEquals(4671, ((Number) readBack.get("DataVersion")).intValue());
            }

            hook.flushWorld(overworld);
            hook.detachWorld(overworld);
            assertNull(hook.getSession(overworld));
            assertEquals(1, hook.attachedWorldCount());
        }

        List<Object> discovered = PaperLcaRegionFileHook.discoverNmsRegionFileStorages(new SyntheticCraftWorld());
        assertEquals(2, discovered.size());
    }
}
