package io.github.dailystruggle.rtp.fabric.server;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link FabricServerAccessor}'s memoized multi-candidate player lookup
 * (Path 3: Mojang method names + Intermediary method names).
 *
 * <p>Verifies:
 * <ul>
 *   <li>Mojang-mapped server and player list methods are discovered and invoked (MC 26.x).</li>
 *   <li>Intermediary-mapped server and player list methods are discovered and invoked (MC 1.20 - 1.21.x).</li>
 *   <li>Resolved method handles are memoized across repeated lookups.</li>
 *   <li>Unknown / offline players return {@code null} without throwing or logging NoSuchMethodException.</li>
 *   <li>Servers lacking player list methods fail safely returning {@code null}.</li>
 *   <li>{@link FabricServerAccessor#unbindServer()} clears the cached method handles.</li>
 *   <li>Bytecode verification of candidate method signatures directly in Loom-cached 1.21.x intermediary and 26.x deobfuscated jars.</li>
 * </ul>
 */
class FabricServerAccessorPlayerLookupTest {

    // --- Fake Mojang-mapped classes (MC 26.x deobf shape) ---
    public static final class FakeMojangServer {
        private final FakeMojangPlayerList playerList;

        public FakeMojangServer(FakeMojangPlayerList playerList) {
            this.playerList = playerList;
        }

        public FakeMojangPlayerList getPlayerList() {
            return playerList;
        }
    }

    public static final class FakeMojangPlayerList {
        private final Map<UUID, Object> byUuid = new HashMap<>();
        private final Map<String, Object> byName = new HashMap<>();

        public void addPlayer(UUID uuid, String name, Object player) {
            byUuid.put(uuid, player);
            byName.put(name, player);
        }

        public Object getPlayer(UUID uuid) {
            return byUuid.get(uuid);
        }

        public Object getPlayerByName(String name) {
            return byName.get(name);
        }
    }

    // --- Fake Intermediary-mapped classes (MC 1.20 - 1.21.x shape) ---
    public static final class FakeIntermediaryServer {
        private final FakeIntermediaryPlayerList playerList;

        public FakeIntermediaryServer(FakeIntermediaryPlayerList playerList) {
            this.playerList = playerList;
        }

        public FakeIntermediaryPlayerList method_3760() {
            return playerList;
        }
    }

    public static final class FakeIntermediaryPlayerList {
        private final Map<UUID, Object> byUuid = new HashMap<>();
        private final Map<String, Object> byName = new HashMap<>();

        public void addPlayer(UUID uuid, String name, Object player) {
            byUuid.put(uuid, player);
            byName.put(name, player);
        }

        // Standard Intermediary name for getPlayer(UUID)
        public Object method_14602(UUID uuid) {
            return byUuid.get(uuid);
        }

        // Standard Intermediary name for getPlayerByName(String)
        public Object method_14566(String name) {
            return byName.get(name);
        }
    }

    // --- Fake Intermediary-mapped classes with 1.21.11 variant name ---
    public static final class FakeIntermediary12111Server {
        private final FakeIntermediary12111PlayerList playerList;

        public FakeIntermediary12111Server(FakeIntermediary12111PlayerList playerList) {
            this.playerList = playerList;
        }

        public FakeIntermediary12111PlayerList method_3760() {
            return playerList;
        }
    }

    public static final class FakeIntermediary12111PlayerList {
        private final Map<UUID, Object> byUuid = new HashMap<>();
        private final Map<String, Object> byName = new HashMap<>();

        public void addPlayer(UUID uuid, String name, Object player) {
            byUuid.put(uuid, player);
            byName.put(name, player);
        }

        public Object method_14602(UUID uuid) {
            return byUuid.get(uuid);
        }

        // 1.21.11+ alternate name for getPlayer(String)
        public Object method_74003(String name) {
            return byName.get(name);
        }
    }

    // --- Fake Intermediary-mapped classes with legacy fallback names ---
    public static final class FakeIntermediaryFallbackServer {
        private final FakeIntermediaryFallbackPlayerList playerList;

        public FakeIntermediaryFallbackServer(FakeIntermediaryFallbackPlayerList playerList) {
            this.playerList = playerList;
        }

        public FakeIntermediaryFallbackPlayerList method_3760() {
            return playerList;
        }
    }

    public static final class FakeIntermediaryFallbackPlayerList {
        private final Map<UUID, Object> byUuid = new HashMap<>();
        private final Map<String, Object> byName = new HashMap<>();

        public void addPlayer(UUID uuid, String name, Object player) {
            byUuid.put(uuid, player);
            byName.put(name, player);
        }

        public Object method_14574(UUID uuid) {
            return byUuid.get(uuid);
        }

        public Object method_14582(String name) {
            return byName.get(name);
        }
    }

    // --- Fake server with no player list method ---
    public static final class FakeEmptyServer {}

    @Test
    void lookupWithMojangNamesResolvesPlayerAndMemoizes() {
        FabricServerAccessor accessor = new FabricServerAccessor();
        FakeMojangPlayerList list = new FakeMojangPlayerList();
        UUID id = UUID.randomUUID();
        Object playerObj = new Object();
        list.addPlayer(id, "TestPlayer", playerObj);

        FakeMojangServer server = new FakeMojangServer(list);

        Object foundByUuid = accessor.lookupOnlineServerPlayerByUuid(server, id);
        assertSame(playerObj, foundByUuid, "lookup by UUID should find player using Mojang method names");

        Object foundByName = accessor.lookupOnlineServerPlayerByName(server, "TestPlayer");
        assertSame(playerObj, foundByName, "lookup by name should find player using Mojang method names");

        Method listMethod1 = accessor.getCachedGetPlayerListMethod();
        assertNotNull(listMethod1);
        assertEquals("getPlayerList", listMethod1.getName());

        Method uuidMethod1 = accessor.getCachedGetPlayerByUuidMethod();
        assertNotNull(uuidMethod1);
        assertEquals("getPlayer", uuidMethod1.getName());

        Method nameMethod1 = accessor.getCachedGetPlayerByNameMethod();
        assertNotNull(nameMethod1);
        assertEquals("getPlayerByName", nameMethod1.getName());

        // Subsequent lookup re-uses the same memoized methods
        Object foundAgain = accessor.lookupOnlineServerPlayerByUuid(server, id);
        assertSame(playerObj, foundAgain);
        assertSame(listMethod1, accessor.getCachedGetPlayerListMethod());
        assertSame(uuidMethod1, accessor.getCachedGetPlayerByUuidMethod());
    }

    @Test
    void lookupWithIntermediaryNamesResolvesPlayerAndMemoizes() {
        FabricServerAccessor accessor = new FabricServerAccessor();
        FakeIntermediaryPlayerList list = new FakeIntermediaryPlayerList();
        UUID id = UUID.randomUUID();
        Object playerObj = new Object();
        list.addPlayer(id, "IntermediaryPlayer", playerObj);

        FakeIntermediaryServer server = new FakeIntermediaryServer(list);

        Object foundByUuid = accessor.lookupOnlineServerPlayerByUuid(server, id);
        assertSame(playerObj, foundByUuid, "lookup by UUID should find player using Intermediary method names");

        Object foundByName = accessor.lookupOnlineServerPlayerByName(server, "IntermediaryPlayer");
        assertSame(playerObj, foundByName, "lookup by name should find player using Intermediary method names");

        Method listMethod = accessor.getCachedGetPlayerListMethod();
        assertNotNull(listMethod);
        assertEquals("method_3760", listMethod.getName());

        Method uuidMethod = accessor.getCachedGetPlayerByUuidMethod();
        assertNotNull(uuidMethod);
        assertEquals("method_14602", uuidMethod.getName());

        Method nameMethod = accessor.getCachedGetPlayerByNameMethod();
        assertNotNull(nameMethod);
        assertEquals("method_14566", nameMethod.getName());
    }

    @Test
    void lookupWithIntermediary12111VariantResolvesPlayerAndMemoizes() {
        FabricServerAccessor accessor = new FabricServerAccessor();
        FakeIntermediary12111PlayerList list = new FakeIntermediary12111PlayerList();
        UUID id = UUID.randomUUID();
        Object playerObj = new Object();
        list.addPlayer(id, "Intermediary12111Player", playerObj);

        FakeIntermediary12111Server server = new FakeIntermediary12111Server(list);

        Object foundByUuid = accessor.lookupOnlineServerPlayerByUuid(server, id);
        assertSame(playerObj, foundByUuid);

        Object foundByName = accessor.lookupOnlineServerPlayerByName(server, "Intermediary12111Player");
        assertSame(playerObj, foundByName);

        Method nameMethod = accessor.getCachedGetPlayerByNameMethod();
        assertNotNull(nameMethod);
        assertEquals("method_74003", nameMethod.getName());
    }

    @Test
    void lookupWithIntermediaryFallbackNamesResolvesPlayerAndMemoizes() {
        FabricServerAccessor accessor = new FabricServerAccessor();
        FakeIntermediaryFallbackPlayerList list = new FakeIntermediaryFallbackPlayerList();
        UUID id = UUID.randomUUID();
        Object playerObj = new Object();
        list.addPlayer(id, "FallbackPlayer", playerObj);

        FakeIntermediaryFallbackServer server = new FakeIntermediaryFallbackServer(list);

        Object foundByUuid = accessor.lookupOnlineServerPlayerByUuid(server, id);
        assertSame(playerObj, foundByUuid);

        Object foundByName = accessor.lookupOnlineServerPlayerByName(server, "FallbackPlayer");
        assertSame(playerObj, foundByName);

        Method uuidMethod = accessor.getCachedGetPlayerByUuidMethod();
        assertNotNull(uuidMethod);
        assertEquals("method_14574", uuidMethod.getName());

        Method nameMethod = accessor.getCachedGetPlayerByNameMethod();
        assertNotNull(nameMethod);
        assertEquals("method_14582", nameMethod.getName());
    }

    @Test
    void lookupMissingPlayerReturnsNullWithoutException() {
        FabricServerAccessor accessor = new FabricServerAccessor();
        FakeIntermediaryPlayerList list = new FakeIntermediaryPlayerList();
        FakeIntermediaryServer server = new FakeIntermediaryServer(list);

        UUID nonExistentUuid = UUID.randomUUID();
        assertNull(accessor.lookupOnlineServerPlayerByUuid(server, nonExistentUuid));
        assertNull(accessor.lookupOnlineServerPlayerByName(server, "NonExistent"));
        assertNull(accessor.lookupOnlineServerPlayerByUuid(server, null));
        assertNull(accessor.lookupOnlineServerPlayerByName(server, null));
    }

    @Test
    void lookupWithUnsupportedServerReturnsNullCleanly() {
        FabricServerAccessor accessor = new FabricServerAccessor();
        FakeEmptyServer emptyServer = new FakeEmptyServer();

        assertNull(accessor.lookupOnlineServerPlayerByUuid(emptyServer, UUID.randomUUID()));
        assertNull(accessor.lookupOnlineServerPlayerByName(emptyServer, "Anyone"));
        assertNull(accessor.getCachedGetPlayerListMethod());
    }

    @Test
    void unbindServerClearsCachedHandles() {
        FabricServerAccessor accessor = new FabricServerAccessor();
        FakeMojangPlayerList list = new FakeMojangPlayerList();
        FakeMojangServer server = new FakeMojangServer(list);

        accessor.lookupOnlineServerPlayerByUuid(server, UUID.randomUUID());
        assertNotNull(accessor.getCachedGetPlayerListMethod());

        accessor.unbindServer();

        assertNull(accessor.getCachedGetPlayerListMethod());
        assertNull(accessor.getCachedGetPlayerByUuidMethod());
        assertNull(accessor.getCachedGetPlayerByNameMethod());
    }

    @Test
    void verifiesBytecodeSignaturesInReal121IntermediaryJarIfPresent() throws Exception {
        Path cacheDir = Paths.get(System.getProperty("user.home"), ".gradle", "caches", "fabric-loom", "minecraftMaven", "net", "minecraft", "minecraft-merged-intermediary");
        if (!Files.exists(cacheDir)) return;
        try (var stream = Files.walk(cacheDir, 4)) {
            Optional<Path> jarOpt = stream
                    .filter(p -> p.toString().endsWith(".jar") && !p.toString().endsWith("-sources.jar") && p.toString().contains("1.21.11"))
                    .findFirst();
            if (jarOpt.isEmpty()) return;
            try (ZipFile zf = new ZipFile(jarOpt.get().toFile())) {
                ZipEntry serverEntry = zf.getEntry("net/minecraft/class_3176.class");
                assertNotNull(serverEntry, "class_3176.class must exist in 1.21.11 intermediary jar");
                try (InputStream in = zf.getInputStream(serverEntry)) {
                    Set<String> sigs = extractMethodSignatures(in);
                    assertTrue(sigs.contains("method_3760()Lnet/minecraft/class_3324;"),
                            "class_3176 must have method_3760()Lnet/minecraft/class_3324;");
                }

                ZipEntry playerListEntry = zf.getEntry("net/minecraft/class_3324.class");
                assertNotNull(playerListEntry, "class_3324.class must exist in 1.21.11 intermediary jar");
                try (InputStream in = zf.getInputStream(playerListEntry)) {
                    Set<String> sigs = extractMethodSignatures(in);
                    assertTrue(sigs.contains("method_14602(Ljava/util/UUID;)Lnet/minecraft/class_3222;"),
                            "class_3324 must have method_14602(UUID)->class_3222");
                    assertTrue(sigs.contains("method_14566(Ljava/lang/String;)Lnet/minecraft/class_3222;"),
                            "class_3324 must have method_14566(String)->class_3222");
                }
            }
        }
    }

    @Test
    void verifiesBytecodeSignaturesInReal26DeobfJarIfPresent() throws Exception {
        Path cacheDir = Paths.get(System.getProperty("user.home"), ".gradle", "caches", "fabric-loom", "minecraftMaven", "net", "minecraft", "minecraft-merged-deobf");
        if (!Files.exists(cacheDir)) return;
        try (var stream = Files.walk(cacheDir, 4)) {
            Optional<Path> jarOpt = stream
                    .filter(p -> p.toString().endsWith(".jar") && !p.toString().endsWith("-sources.jar"))
                    .findFirst();
            if (jarOpt.isEmpty()) return;
            try (ZipFile zf = new ZipFile(jarOpt.get().toFile())) {
                ZipEntry serverEntry = zf.getEntry("net/minecraft/server/MinecraftServer.class");
                assertNotNull(serverEntry, "MinecraftServer.class must exist in 26.x deobf jar");
                try (InputStream in = zf.getInputStream(serverEntry)) {
                    Set<String> sigs = extractMethodSignatures(in);
                    assertTrue(sigs.contains("getPlayerList()Lnet/minecraft/server/players/PlayerList;"),
                            "MinecraftServer must have getPlayerList()");
                }

                ZipEntry playerListEntry = zf.getEntry("net/minecraft/server/players/PlayerList.class");
                assertNotNull(playerListEntry, "PlayerList.class must exist in 26.x deobf jar");
                try (InputStream in = zf.getInputStream(playerListEntry)) {
                    Set<String> sigs = extractMethodSignatures(in);
                    assertTrue(sigs.contains("getPlayer(Ljava/util/UUID;)Lnet/minecraft/server/level/ServerPlayer;"),
                            "PlayerList must have getPlayer(UUID)");
                    assertTrue(sigs.contains("getPlayerByName(Ljava/lang/String;)Lnet/minecraft/server/level/ServerPlayer;"),
                            "PlayerList must have getPlayerByName(String)");
                }
            }
        }
    }

    private static Set<String> extractMethodSignatures(InputStream in) throws IOException {
        DataInputStream dis = new DataInputStream(in);
        int magic = dis.readInt();
        if (magic != 0xCAFEBABE) return Collections.emptySet();
        dis.readUnsignedShort(); // minor
        dis.readUnsignedShort(); // major
        int cpCount = dis.readUnsignedShort();
        String[] strings = new String[cpCount];
        for (int i = 1; i < cpCount; i++) {
            int tag = dis.readUnsignedByte();
            switch (tag) {
                case 1 -> strings[i] = dis.readUTF();
                case 3, 4 -> dis.skipBytes(4);
                case 5, 6 -> {
                    dis.skipBytes(8);
                    i++;
                }
                case 7, 8, 16, 19, 20 -> dis.skipBytes(2);
                case 9, 10, 11, 12, 18 -> dis.skipBytes(4);
                case 15 -> dis.skipBytes(3);
                default -> throw new IOException("Unknown constant pool tag: " + tag);
            }
        }
        dis.readUnsignedShort(); // access_flags
        dis.readUnsignedShort(); // this_class
        dis.readUnsignedShort(); // super_class
        int ifaceCount = dis.readUnsignedShort();
        dis.skipBytes(ifaceCount * 2);
        int fieldsCount = dis.readUnsignedShort();
        for (int i = 0; i < fieldsCount; i++) {
            dis.skipBytes(6);
            int attrCount = dis.readUnsignedShort();
            for (int a = 0; a < attrCount; a++) {
                dis.skipBytes(2);
                int len = dis.readInt();
                dis.skipBytes(len);
            }
        }
        int methodsCount = dis.readUnsignedShort();
        Set<String> methods = new HashSet<>();
        for (int i = 0; i < methodsCount; i++) {
            dis.skipBytes(2);
            int nameIndex = dis.readUnsignedShort();
            int descIndex = dis.readUnsignedShort();
            methods.add(strings[nameIndex] + strings[descIndex]);
            int attrCount = dis.readUnsignedShort();
            for (int a = 0; a < attrCount; a++) {
                dis.skipBytes(2);
                int len = dis.readInt();
                dis.skipBytes(len);
            }
        }
        return methods;
    }
}
