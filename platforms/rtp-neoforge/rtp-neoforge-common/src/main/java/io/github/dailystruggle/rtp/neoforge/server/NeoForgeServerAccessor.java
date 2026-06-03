package io.github.dailystruggle.rtp.neoforge.server;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.selection.ILocationGenerator;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.selection.region.LocationGenerator;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder;
import io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe;
import io.github.dailystruggle.rtp.common.tasks.TimeBoundTaskPipe;
import io.github.dailystruggle.rtp.common.tools.PlaceholderProvider;
import io.github.dailystruggle.rtp.neoforge.player.NeoForgeRTPPlayer;
import io.github.dailystruggle.rtp.neoforge.scheduling.NeoForgeScheduler;
import io.github.dailystruggle.rtp.neoforge.tools.NeoForgeAnsiText;
import io.github.dailystruggle.rtp.neoforge.tools.NeoForgeLegacyText;
import io.github.dailystruggle.rtp.neoforge.world.NeoForgeRTPWorld;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * NeoForge platform implementation of {@link RTPServerAccessor} (Phase N2.1),
 * the NeoForge analogue of {@code FabricServerAccessor}.
 *
 * <p><b>Mojmap-at-runtime simplification.</b> NeoForge ships Mojang-mapped names
 * at runtime, so this class compiles directly against {@code net.minecraft} and
 * constructs {@link NeoForgeRTPWorld} / {@link NeoForgeRTPPlayer} directly —
 * none of Fabric's reflective obf/deobf carrier dispatch is needed.</p>
 *
 * <p><b>Deferred to later N2 sub-phases:</b> the event-bridge wiring (N2.3) that
 * populates the player/world maps, the player lifecycle hook (a
 * {@code NoopPlayerLifecycleHook} placeholder is used until N2.3), and the
 * permission/menu sinks (N2.5/N2.6). No {@code org.bukkit.*} imports.</p>
 */
public final class NeoForgeServerAccessor implements RTPServerAccessor {

    private final NeoForgeScheduler scheduler = new NeoForgeScheduler();
    private final Map<String, WorldBorder> nativeWorldBorderCache = new ConcurrentHashMap<>();
    private Function<String, ?> worldBorderFunction = this::createNativeWorldBorder;
    private Function<String, ?> shapeFunction = this::createNativeShape;
    private Function<RTPLocation, String> biomeGetter = this::defaultBiomeAt;
    private Function<RTPWorld<?>, Set<String>> biomesGetter = this::defaultBiomesFor;

    private final Map<UUID, RTPPlayer> playersById = new ConcurrentHashMap<>();
    private final Map<String, RTPPlayer> playersByName = new ConcurrentHashMap<>();
    private final Map<String, RTPWorld<?>> worldsByName = new ConcurrentHashMap<>();
    private final Map<UUID, RTPWorld<?>> worldsById = new ConcurrentHashMap<>();
    private volatile @Nullable MinecraftServer server;

    private final NeoForgePlayerLifecycleHook playerLifecycleHook =
            new NeoForgePlayerLifecycleHook();

    /** Bind the running server; rebuild the block-tag snapshot now the registry is live. */
    public void bindServer(MinecraftServer server) {
        this.server = server;
        this.scheduler.setServer(server);
        try {
            rebuildBlockTagSnapshot();
        } catch (Throwable t) {
            log(Level.WARNING,
                    "[RTP][NeoForge] Post-start block-tag snapshot rebuild failed: "
                            + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    public @Nullable MinecraftServer getServer() {
        return this.server;
    }

    public void unbindServer() {
        this.scheduler.clearServer();
        this.server = null;
        this.playersById.clear();
        this.playersByName.clear();
        this.worldsByName.clear();
        this.worldsById.clear();
    }

    // ---------------------------------------------------------------------------
    // World / player registration (typed — Mojmap, no reflective dispatch)
    // ---------------------------------------------------------------------------

    public NeoForgeRTPWorld registerWorld(ServerLevel level) {
        NeoForgeRTPWorld w = new NeoForgeRTPWorld(level);
        worldsByName.put(w.name(), w);
        worldsById.put(w.id(), w);
        return w;
    }

    public void unregisterWorld(ServerLevel level) {
        String name = io.github.dailystruggle.rtp.neoforge.tools.NeoForgeResourceIds
                .locationString(level.dimension());
        RTPWorld<?> removed = worldsByName.remove(name);
        if (removed != null) worldsById.remove(removed.id());
    }

    public RTPPlayer registerPlayer(ServerPlayer player) {
        RTPPlayer existing = playersById.get(player.getUUID());
        if (existing != null) {
            if (existing instanceof NeoForgeRTPPlayer np) np.rebind(player);
            return existing;
        }
        NeoForgeRTPPlayer wrapper = new NeoForgeRTPPlayer(player);
        playersById.put(wrapper.uuid(), wrapper);
        playersByName.put(wrapper.name(), wrapper);
        return wrapper;
    }

    public void unregisterPlayer(UUID uuid) {
        RTPPlayer removed = playersById.remove(uuid);
        if (removed != null) {
            playersByName.remove(removed.name());
            if (removed instanceof NeoForgeRTPPlayer np) np.unbind();
        }
    }

    // ---------------------------------------------------------------------------
    // Location generator (REQ-RTP-S-006 require-by-contract)
    // ---------------------------------------------------------------------------

    @Override
    public ILocationGenerator getLocationGenerator() {
        if (RTP.getInstance() == null) {
            throw new IllegalStateException(
                "[RTP] NeoForgeServerAccessor.getLocationGenerator() called before rtp-core is initialized."
                    + " This violates REQ-RTP-S-006 (require-by-contract API entry points).");
        }
        return new LocationGenerator();
    }

    @Override
    public String getServerVersion() {
        MinecraftServer s = server;
        return s == null ? "unknown" : s.getServerVersion();
    }

    @Override
    public String getPluginVersion() {
        try {
            return net.neoforged.fml.ModList.get().getModContainerById("rtp")
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("unknown");
        } catch (Throwable t) {
            return "unknown";
        }
    }

    @Override
    public String getPlatform() {
        return "neoforge";
    }

    @Override
    public Integer getServerIntVersion() {
        String v = getServerVersion();
        String[] parts = v.split("\\.");
        if (parts.length >= 2) {
            try { return Integer.parseInt(parts[1]); } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    @Override
    public RTPWorld<?> getRTPWorld(String name) {
        if (name == null) return null;
        RTPWorld<?> w = worldsByName.get(name);
        if (w != null) return w;
        for (RTPWorld<?> candidate : worldsByName.values()) {
            if (candidate.name().equals(name) || candidate.name().endsWith(":" + name)) {
                return candidate;
            }
        }
        return null;
    }

    @Override
    public RTPWorld<?> getRTPWorld(UUID id) {
        return id == null ? null : worldsById.get(id);
    }

    @Override
    public List<RTPWorld<?>> getRTPWorlds() {
        return new ArrayList<>(worldsByName.values());
    }

    @Override
    public RTPPlayer getPlayer(UUID uuid) {
        if (uuid == null) return null;
        RTPPlayer cached = playersById.get(uuid);
        if (cached != null) return cached;
        return lazyResolveByUuid(uuid);
    }

    @Override
    public RTPPlayer getPlayer(String name) {
        if (name == null) return null;
        RTPPlayer cached = playersByName.get(name);
        if (cached != null) return cached;
        MinecraftServer s = server;
        if (s == null) return null;
        ServerPlayer sp = s.getPlayerList().getPlayerByName(name);
        return sp == null ? null : registerPlayer(sp);
    }

    private RTPPlayer lazyResolveByUuid(UUID uuid) {
        MinecraftServer s = server;
        if (s == null) return null;
        ServerPlayer sp = s.getPlayerList().getPlayer(uuid);
        return sp == null ? null : registerPlayer(sp);
    }

    public Set<String> getOnlinePlayerNames() {
        if (playersByName.isEmpty()) return Collections.emptySet();
        return new HashSet<>(playersByName.keySet());
    }

    @Override
    public RTPPlayer getConsolePlayer() {
        return null;
    }

    @Override
    public RTPCommandSender getSender(UUID uuid) {
        if (uuid == null) return null;
        if (uuid.equals(RTPAPI.serverId)) {
            return new NeoForgeConsoleSender(server);
        }
        RTPPlayer cached = playersById.get(uuid);
        if (cached != null) return cached;
        return lazyResolveByUuid(uuid);
    }

    @Override
    public long overTime() {
        return 0L;
    }

    @Override
    public File getPluginDirectory() {
        File dir = net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("rtp").toFile();
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
            RTP.log(Level.WARNING,
                    "NeoForgeServerAccessor: failed to create plugin directory " + dir.getAbsolutePath());
        }
        return dir;
    }

    @Override
    public io.github.dailystruggle.rtp.api.server.PlayerLifecycleHook getPlayerLifecycleHook() {
        return playerLifecycleHook;
    }

    /**
     * Typed accessor for the NeoForge lifecycle hook, used by
     * {@code NeoForgeEventBridge} to fan join / quit UUID events out to
     * platform-agnostic subscribers (ADR-049).
     */
    public NeoForgePlayerLifecycleHook getNeoForgePlayerLifecycleHook() {
        return playerLifecycleHook;
    }

    // ---------------------------------------------------------------------------
    // Messaging
    // ---------------------------------------------------------------------------

    @Override
    public void sendMessage(UUID target, MessagesKeys msgType, String tag) {
        if (target == null || msgType == null) return;
        String template = lookupMessageTemplate(msgType);
        if (template == null) return;
        sendMessage(target, template, tag);
    }

    @Override
    public void sendMessage(UUID target1, UUID target2, MessagesKeys msgType, String tag) {
        if (msgType == null) return;
        String template = lookupMessageTemplate(msgType);
        if (template == null) return;
        if (target1 != null) sendMessage(target1, template, tag);
        if (target2 != null && !target2.equals(target1)) sendMessage(target2, template, tag);
    }

    @Override
    public void sendMessage(UUID target, String message, String tag) {
        if (target == null || message == null) return;
        RTPCommandSender sender = getSender(target);
        if (sender == null) return;
        sender.sendMessage(format(target, message));
    }

    @Override
    public void sendMessageAndSuggest(UUID target, String message, String suggestion) {
        if (target == null || message == null) return;
        String formattedMsg = format(target, message);
        try {
            RTPPlayer player = getPlayer(target);
            if (player instanceof NeoForgeRTPPlayer np && np.isOnline()) {
                np.sendComponent(NeoForgeLegacyText.parseInteractive(formattedMsg, null, suggestion));
                return;
            }
            if (player != null && player.isOnline()) {
                player.sendMessage(formattedMsg);
                return;
            }
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[RTP] sendMessageAndSuggest interactive path failed ("
                            + t.getClass().getSimpleName() + "): " + t.getMessage());
        }
        String composed = (suggestion == null || suggestion.isEmpty())
                ? formattedMsg : formattedMsg + " " + suggestion;
        RTPCommandSender sender = getSender(target);
        if (sender != null) sender.sendMessage(composed);
    }

    @Override
    public void sendMessage(UUID sender, UUID target, String message, String tag) {
        if (target != null) sendMessage(target, message, tag);
        if (sender != null && !sender.equals(target)) sendMessage(sender, message, tag);
    }

    @Override
    public void sendMessage(RTPCommandSender target, String message, String hover, String click,
                            String tag) {
        sendInteractiveMessage(target, message, hover, click, NeoForgeLegacyText.ClickKind.SUGGEST);
    }

    @Override
    public void sendMessageWithRunCommand(RTPCommandSender target, String message,
                                          String hover, String runCommand, String tag) {
        sendInteractiveMessage(target, message, hover, runCommand, NeoForgeLegacyText.ClickKind.RUN);
    }

    private void sendInteractiveMessage(RTPCommandSender target, String message,
                                        String hover, String click,
                                        NeoForgeLegacyText.ClickKind clickKind) {
        if (target == null || message == null) return;
        String formattedMsg = format(target.uuid(), message);
        String formattedHover = (hover == null) ? null : format(target.uuid(), hover);
        try {
            RTPPlayer player = getPlayer(target.uuid());
            if (player instanceof NeoForgeRTPPlayer np && np.isOnline()) {
                np.sendComponent(
                        NeoForgeLegacyText.parseInteractive(formattedMsg, formattedHover, click, clickKind));
                return;
            }
            if (player != null && player.isOnline()) {
                player.sendMessage(formattedMsg);
                return;
            }
        } catch (Throwable t) {
            RTP.log(Level.WARNING,
                    "[RTP] sendMessage(hover/click) interactive path failed ("
                            + t.getClass().getSimpleName() + "): " + t.getMessage());
        }
        target.sendMessage(formattedMsg);
    }

    @Override
    public String format(UUID player, String text) {
        if (text == null) return "";
        UUID uuid = (player != null) ? player : RTPAPI.serverId;
        text = PlaceholderProvider.fillPlaceholders(text, uuid);
        if (langParser() != null) {
            text = PlaceholderProvider.fillNumericPlaceholders(text);
        }
        return text;
    }

    @Override
    public String formatNoColor(UUID player, String text) {
        if (text == null) return "";
        UUID uuid = (player != null) ? player : RTPAPI.serverId;
        text = PlaceholderProvider.fillPlaceholders(text, uuid);
        if (langParser() != null) {
            text = PlaceholderProvider.fillNumericPlaceholders(text);
        }
        return NeoForgeAnsiText.stripColor(text);
    }

    @SuppressWarnings("unchecked")
    private static @Nullable ConfigParser<MessagesKeys> langParser() {
        if (RTP.configs == null) return null;
        return (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
    }

    private static @Nullable String lookupMessageTemplate(MessagesKeys key) {
        if (RTP.configs == null) return null;
        ConfigParser<MessagesKeys> lang = langParser();
        if (lang == null) return null;
        Object v = lang.getConfigValue(key, "");
        return v == null ? null : v.toString();
    }

    // ---------------------------------------------------------------------------
    // Logging
    // ---------------------------------------------------------------------------

    @Override
    public void log(Level level, String msg) {
        if (!isLoggable(level)) return;
        String formatted = (msg == null) ? "" : formatForLog(msg);
        String ansi = NeoForgeAnsiText.toAnsiString(prefixForLevel(level) + formatted);
        org.apache.logging.log4j.LogManager.getLogger("RTP").log(toLog4jLevel(level), ansi);
    }

    @Override
    public void log(Level level, String msg, Throwable throwable) {
        if (!isLoggable(level)) return;
        String formatted = (msg == null) ? "" : formatForLog(msg);
        String ansi = NeoForgeAnsiText.toAnsiString(prefixForLevel(level) + formatted);
        org.apache.logging.log4j.LogManager.getLogger("RTP").log(toLog4jLevel(level), ansi, throwable);
    }

    private String formatForLog(String msg) {
        try {
            return format(null, msg);
        } catch (Throwable t) {
            return msg;
        }
    }

    private static volatile String cachedMinLevelRaw = "ALL";
    private static volatile Level cachedMinLevel = Level.ALL;

    private static boolean isLoggable(Level level) {
        if (level == null) return true;
        return level.intValue() >= resolveMinLevel().intValue();
    }

    @SuppressWarnings("unchecked")
    private static Level resolveMinLevel() {
        try {
            if (RTP.configs == null) return Level.ALL;
            ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys> logging =
                    (ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys>)
                            RTP.configs.getParser(
                                    io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys.class);
            if (logging == null) return Level.ALL;
            Object raw = logging.getConfigValue(
                    io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys.min_level, "ALL");
            String name = (raw == null) ? "ALL" : raw.toString().trim().toUpperCase(java.util.Locale.ROOT);
            if (name.isEmpty()) name = "ALL";
            if (name.equals(cachedMinLevelRaw)) return cachedMinLevel;
            Level parsed;
            try {
                parsed = Level.parse(name);
            } catch (IllegalArgumentException ex) {
                parsed = Level.ALL;
            }
            cachedMinLevelRaw = name;
            cachedMinLevel = parsed;
            return parsed;
        } catch (Throwable t) {
            return Level.ALL;
        }
    }

    private static org.apache.logging.log4j.Level toLog4jLevel(Level level) {
        if (level == null) return org.apache.logging.log4j.Level.INFO;
        int v = level.intValue();
        if (v >= 1000) return org.apache.logging.log4j.Level.ERROR;
        if (v >= 900)  return org.apache.logging.log4j.Level.WARN;
        return org.apache.logging.log4j.Level.INFO;
    }

    private static String prefixForLevel(Level level) {
        if (level == null) return "";
        int v = level.intValue();
        if (v >= 1000) return "&c";
        if (v >= 900)  return "&e";
        if (v >= 800)  return "";
        if (v >= 700)  return "&a";
        if (v >= 500)  return "&b";
        return "";
    }

    @Override
    public void announce(String msg, String permission, String tag) {
        if (msg == null) return;
        for (RTPPlayer p : playersById.values()) {
            if (permission == null || permission.isEmpty() || p.hasPermission(permission)) {
                p.sendMessage(format(p.uuid(), msg));
            }
        }
        org.apache.logging.log4j.LogManager.getLogger("RTP")
                .info(NeoForgeAnsiText.toAnsiString(format(null, msg)));
    }

    // ---------------------------------------------------------------------------
    // Biomes / materials / block tags (typed Mojmap)
    // ---------------------------------------------------------------------------

    @Override
    public Set<String> getBiomes(RTPWorld<?> rtpWorld) {
        return biomesGetter.apply(rtpWorld);
    }

    @Override
    public Set<String> getBiomes() {
        return biomesGetter.apply(null);
    }

    @Override
    public boolean isPrimaryThread() {
        MinecraftServer s = server;
        if (s == null) return false;
        try {
            return s.isSameThread();
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public Set<String> materials() {
        Set<String> out = new HashSet<>();
        // keySet() element type is the (renamable) identifier class; iterate as
        // Object so no checkcast to a version-volatile type is emitted.
        for (Object key : BuiltInRegistries.BLOCK.keySet()) {
            if (key != null) out.add(key.toString().toUpperCase());
        }
        return out;
    }

    private volatile Map<String, Set<String>> blockTagSnapshot;

    @Override
    public Map<String, Set<String>> blockTagSnapshot() {
        Map<String, Set<String>> snap = this.blockTagSnapshot;
        if (snap != null) return snap;
        synchronized (this) {
            snap = this.blockTagSnapshot;
            if (snap != null) return snap;
            snap = buildBlockTagSnapshot();
            this.blockTagSnapshot = snap;
            return snap;
        }
    }

    @Override
    public void rebuildBlockTagSnapshot() {
        synchronized (this) {
            this.blockTagSnapshot = buildBlockTagSnapshot();
        }
    }

    private Map<String, Set<String>> buildBlockTagSnapshot() {
        if (this.server == null) {
            return Collections.emptyMap();
        }
        Map<String, Set<String>> out = new java.util.HashMap<>();
        try {
            for (net.minecraft.world.level.block.Block block : BuiltInRegistries.BLOCK) {
                String materialName = io.github.dailystruggle.rtp.neoforge.tools.NeoForgeResourceIds
                        .registryKeyString(BuiltInRegistries.BLOCK, block);
                if (materialName == null) continue;
                materialName = materialName.toUpperCase();
                net.minecraft.core.Holder.Reference<net.minecraft.world.level.block.Block> holder;
                try {
                    holder = block.builtInRegistryHolder();
                } catch (Throwable t) {
                    continue;
                }
                if (holder == null) continue;
                java.util.stream.Stream<net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block>>
                        tagStream;
                try {
                    tagStream = holder.tags();
                } catch (Throwable t) {
                    continue;
                }
                if (tagStream == null) continue;
                java.util.Iterator<net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block>> it =
                        tagStream.iterator();
                while (it.hasNext()) {
                    net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block> tagKey = it.next();
                    if (tagKey == null) continue;
                    Object tagId = io.github.dailystruggle.rtp.neoforge.tools.NeoForgeResourceIds
                            .location(tagKey);
                    if (tagId == null) continue;
                    String key = io.github.dailystruggle.rtp.neoforge.tools.NeoForgeResourceIds.namespace(tagId)
                            + ":" + io.github.dailystruggle.rtp.neoforge.tools.NeoForgeResourceIds.path(tagId);
                    out.computeIfAbsent(key, k -> new HashSet<>()).add(materialName);
                }
            }
        } catch (Throwable e) {
            log(Level.WARNING,
                    "[RTP][NeoForge] Failed to snapshot block tag registry: "
                            + e.getClass().getName() + ": " + e.getMessage(), e);
            return Collections.emptyMap();
        }
        if (out.isEmpty()) return Collections.emptyMap();
        Map<String, Set<String>> immutable = new java.util.HashMap<>(out.size());
        for (Map.Entry<String, Set<String>> e : out.entrySet()) {
            immutable.put(e.getKey(), Collections.unmodifiableSet(e.getValue()));
        }
        return Collections.unmodifiableMap(immutable);
    }

    @Override
    public void setBiomeGetter(Function<RTPLocation, String> getter) {
        this.biomeGetter = getter;
    }

    @Override
    public void setBiomesGetter(Function<RTPWorld<?>, Set<String>> getter) {
        this.biomesGetter = getter;
    }

    private @Nullable String defaultBiomeAt(RTPLocation location) {
        if (location == null) return null;
        RTPWorld<?> w = location.world();
        if (!(w instanceof NeoForgeRTPWorld nw)) return null;
        ServerLevel level = nw.level();
        net.minecraft.core.BlockPos pos =
                new net.minecraft.core.BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        var holder = level.getBiome(pos);
        String raw = holder.unwrapKey()
                .map(k -> io.github.dailystruggle.rtp.neoforge.tools.NeoForgeResourceIds.locationString(k))
                .orElse(null);
        if (raw == null) return null;
        String n = io.github.dailystruggle.rtp.api.configuration
                .PaletteIdentifierNormalizer.normalize(raw);
        return (n != null && !n.isEmpty()) ? n : raw;
    }

    private Set<String> defaultBiomesFor(@Nullable RTPWorld<?> rtpWorld) {
        MinecraftServer s = server;
        if (s == null) return Collections.emptySet();
        Set<String> out = new HashSet<>();
        try {
            net.minecraft.core.Registry<net.minecraft.world.level.biome.Biome> biomeReg =
                    s.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME);
            for (Object loc : biomeReg.keySet()) {
                if (loc == null) continue;
                String raw = loc.toString();
                String n = io.github.dailystruggle.rtp.api.configuration
                        .PaletteIdentifierNormalizer.normalize(raw);
                if (n != null && !n.isEmpty()) out.add(n);
                if (!raw.isEmpty()) out.add(raw);
            }
        } catch (Throwable t) {
            log(Level.WARNING, "[RTP][NeoForge] defaultBiomesFor failed: " + t.getMessage());
        }
        return out;
    }

    // ---------------------------------------------------------------------------
    // World border / shape
    // ---------------------------------------------------------------------------

    protected @Nullable WorldBorder createNativeWorldBorder(String worldName) {
        return nativeWorldBorderCache.computeIfAbsent(worldName, s -> {
            RTPWorld<?> rtpWorld = getRTPWorld(s);
            if (!(rtpWorld instanceof NeoForgeRTPWorld nw)) return null;
            ServerLevel level = nw.level();
            net.minecraft.world.level.border.WorldBorder mcBorder = level.getWorldBorder();
            return new WorldBorder(
                    () -> {
                        Shape<?> shape =
                                (Shape<?>) RTP.factoryMap.get(RTP.factoryNames.shape).get("SQUARE");
                        if (shape instanceof Square square) {
                            square.set(GenericMemoryShapeParams.radius, (long) (mcBorder.getSize() / 32.0));
                            square.set(GenericMemoryShapeParams.centerX, (long) (mcBorder.getCenterX() / 16.0));
                            square.set(GenericMemoryShapeParams.centerZ, (long) (mcBorder.getCenterZ() / 16.0));
                        }
                        return shape;
                    },
                    rtpLocation ->
                            level.getWorldBorder().isWithinBounds((double) rtpLocation.x(), (double) rtpLocation.z()));
        });
    }

    @Override
    public @Nullable Object getWorldBorder(String worldName) {
        Object res = worldBorderFunction.apply(worldName);
        if (res == null) res = createNativeWorldBorder(worldName);
        return res;
    }

    protected @Nullable Shape<?> createNativeShape(String worldName) {
        RTPWorld<?> rtpWorld = getRTPWorld(worldName);
        if (!(rtpWorld instanceof NeoForgeRTPWorld nw)) return null;
        ServerLevel level = nw.level();
        net.minecraft.world.level.border.WorldBorder mcBorder = level.getWorldBorder();
        Shape<?> shape = (Shape<?>) RTP.factoryMap.get(RTP.factoryNames.shape).get("SQUARE");
        if (shape instanceof Square square) {
            square.set(GenericMemoryShapeParams.radius, (long) (mcBorder.getSize() / 32.0));
            square.set(GenericMemoryShapeParams.centerX, (long) (mcBorder.getCenterX() / 16.0));
            square.set(GenericMemoryShapeParams.centerZ, (long) (mcBorder.getCenterZ() / 16.0));
        }
        return shape;
    }

    @Override
    public @Nullable Object getShape(String name) {
        return shapeFunction.apply(name);
    }

    @Override
    public boolean setWorldBorderFunction(Function<String, ?> function) {
        this.worldBorderFunction = function;
        return true;
    }

    @Override
    public boolean setShapeFunction(Function<String, ?> shapeFunction) {
        this.shapeFunction = shapeFunction;
        return true;
    }

    // ---------------------------------------------------------------------------
    // Pipes / plugin / scheduler / lifecycle
    // ---------------------------------------------------------------------------

    @Override
    public RTPTaskPipe createTaskPipe() {
        return new TimeBoundTaskPipe();
    }

    @Override
    public Object createCachePipe() {
        return new TimeBoundTaskPipe();
    }

    @Override
    public Object getPlugin() {
        try {
            return net.neoforged.fml.ModList.get().getModContainerById("rtp").orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public RTPScheduler getScheduler() {
        return scheduler;
    }

    /**
     * Typed accessor for the NeoForge scheduler, used by {@code RTPNeoForgeMod}
     * to drain the tick-driven task map from the server-tick event. The
     * {@link #getScheduler()} contract returns only the platform-neutral
     * {@link RTPScheduler} interface, which has no {@code tick} method.
     */
    public NeoForgeScheduler getNeoForgeScheduler() {
        return scheduler;
    }

    @Override
    public void stop() {
        unbindServer();
    }

    @Override
    public void start() {
        // Lifecycle is event-driven on NeoForge (RTPNeoForgeMod). No-op.
    }

    @Override
    public void start(Object plugin) {
        start();
    }

    @Override
    public double getTPS(int ticks) {
        try {
            io.github.dailystruggle.metrics.api.MetricsSnapshot snap = RTP.metrics.snapshot();
            double v = (ticks >= 600) ? snap.tps15m : (ticks >= 100 ? snap.tps5m : snap.tps1m);
            if (!Double.isNaN(v)) return v;
        } catch (Throwable ignored) {
            // fall through
        }
        MinecraftServer s = server;
        if (s == null) return 20.0;
        try {
            long nanos = s.getAverageTickTimeNanos();
            if (nanos > 0L) return Math.min(1_000_000_000.0 / nanos, 20.0);
        } catch (Throwable ignored) {
            // fall through
        }
        return 20.0;
    }

    // ---------------------------------------------------------------------------
    // Menu platform surface (ADR-048)
    // ---------------------------------------------------------------------------

    @Override
    public java.util.function.Predicate<String> menuPermissionProbe(UUID player) {
        return node -> {
            if (player == null || node == null) return false;
            try {
                RTPCommandSender sender = getSender(player);
                return sender != null && sender.hasPermission(node);
            } catch (Throwable t) {
                return false;
            }
        };
    }

    @Override
    public Set<String> menuEffectivePermissions(UUID player) {
        if (player == null) return Collections.emptySet();
        try {
            RTPCommandSender sender = getSender(player);
            if (sender == null) return Collections.emptySet();
            Set<String> perms = sender.getEffectivePermissions();
            return perms == null ? Collections.emptySet() : perms;
        } catch (Throwable t) {
            return Collections.emptySet();
        }
    }

    @Override
    public String menuLocale(UUID player) {
        return "en_us";
    }

    @Override
    public String menuRegionDescriptor(UUID player) {
        if (player == null) return "";
        try {
            RTPPlayer p = getPlayer(player);
            if (p instanceof NeoForgeRTPPlayer np) {
                ServerPlayer sp = np.handle();
                if (sp != null) {
                    return io.github.dailystruggle.rtp.neoforge.tools.NeoForgeResourceIds
                            .locationString(sp.serverLevel().dimension());
                }
            }
        } catch (Throwable t) {
            // fall through
        }
        return "";
    }

    /** Minimal console sender backed by the bound {@link MinecraftServer}. */
    private static final class NeoForgeConsoleSender implements RTPCommandSender {
        private final @Nullable MinecraftServer server;

        NeoForgeConsoleSender(@Nullable MinecraftServer server) {
            this.server = server;
        }

        @Override public UUID uuid() { return RTPAPI.serverId; }
        @Override public String name() { return "Console"; }
        @Override public boolean hasPermission(String permission) { return true; }
        @Override public Set<String> getEffectivePermissions() {
            // Console is op-equivalent: full closed-namespace grant set, no
            // LuckPerms / hasPermission probe (rtp-fabric-ADR-011 console variant).
            return io.github.dailystruggle.rtp.neoforge.player
                    .NeoForgeEffectivePermissionsResolver.resolveConsole();
        }
        @Override public long cooldown() { return 0L; }
        @Override public long delay() { return 0L; }

        @Override
        public void performCommand(@Nullable RTPPlayer player, String command) {
            MinecraftServer s = server;
            if (s == null || command == null) return;
            try {
                s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), command);
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        }

        @Override
        public void sendMessage(String message) {
            if (message == null) return;
            org.apache.logging.log4j.LogManager.getLogger("RTP")
                    .info(NeoForgeAnsiText.toAnsiString(message));
        }

        @Override public RTPCommandSender clone() { return new NeoForgeConsoleSender(server); }
    }
}
