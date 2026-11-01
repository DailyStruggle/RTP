package io.github.dailystruggle.helpers.stresstestrtp.fabric;

import com.mojang.brigadier.CommandDispatcher;
import io.github.dailystruggle.helpers.stresstestrtp.CoverageProbe;
import io.github.dailystruggle.helpers.stresstestrtp.StressCoreRuntime;
import io.github.dailystruggle.helpers.stresstestrtp.StressPlayer;
import io.github.dailystruggle.server.api.PlatformFamily;
import net.neoforged.fml.common.Mod;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Thin NeoForge carrier for StressTestRTP (ADR-122 Decision 1, 2, 3, 5).
 */
@Mod("stresstestrtp")
public final class StressTestNeoForgeMod extends ModStressHostBase {

    public StressTestNeoForgeMod() {
        this(Path.of("config", "StressTestRTP"));
        initializeNeoForge();
    }

    StressTestNeoForgeMod(Path dataDir) {
        super(dataDir);
    }

    public void initializeNeoForge() {
        saveDefaultConfig();
        reloadStressConfig();
        registerNeoForgeCallbacks();
        this.runtime = new StressCoreRuntime(this);
        LOG.info("[StressTestRTP] NeoForge carrier initialized; dataFolder=" + dataDir.toAbsolutePath()
                + ", coverage.includes=" + CoverageProbe.includesForLoadedMods(this));
    }

    private void registerNeoForgeCallbacks() {
        try {
            Class<?> neoForgeCls = Class.forName("net.neoforged.neoforge.common.NeoForge");
            Object eventBus = neoForgeCls.getField("EVENT_BUS").get(null);
            Method addListener = eventBus.getClass().getMethod("addListener", Class.class, Consumer.class);

            registerBusEvent(eventBus, addListener, "net.neoforged.neoforge.event.server.ServerStartedEvent",
                    ev -> onServerStarted(invokeAny(ev, "getServer")));
            registerBusEvent(eventBus, addListener, "net.neoforged.neoforge.event.server.ServerStoppingEvent",
                    ev -> onServerStopping());
            registerBusEvent(eventBus, addListener, "net.neoforged.neoforge.event.tick.ServerTickEvent$Post",
                    ev -> onServerTick(invokeAny(ev, "getServer")));
            registerBusEvent(eventBus, addListener, "net.neoforged.neoforge.event.RegisterCommandsEvent",
                    ev -> {
                        Object disp = invokeAny(ev, "getDispatcher");
                        if (disp instanceof CommandDispatcher<?> cd) {
                            registerBrigadierCommand(cd);
                        }
                    });
            registerBusEvent(eventBus, addListener, "net.neoforged.neoforge.event.entity.player.PlayerEvent$PlayerLoggedInEvent",
                    ev -> {
                        Object ent = invokeAny(ev, "getEntity");
                        if (ent != null) {
                            StressPlayer sp = wrapPlayer(ent);
                            lastLocations.put(sp.uuid(), sp.location());
                            for (Consumer<StressPlayer> l : joinListeners) l.accept(sp);
                        }
                    });
            registerBusEvent(eventBus, addListener, "net.neoforged.neoforge.event.entity.player.PlayerEvent$PlayerLoggedOutEvent",
                    ev -> {
                        Object ent = invokeAny(ev, "getEntity");
                        if (ent != null) {
                            StressPlayer sp = wrapPlayer(ent);
                            lastLocations.remove(sp.uuid());
                            for (Consumer<StressPlayer> l : quitListeners) l.accept(sp);
                        }
                    });
            registerBusEvent(eventBus, addListener, "net.neoforged.neoforge.event.level.ChunkEvent$Load",
                    ev -> {
                        Object lvl = invokeAny(ev, "getLevel");
                        Object ch = invokeAny(ev, "getChunk");
                        boolean isNew = Boolean.TRUE.equals(invokeAny(ev, "isNewChunk"));
                        ChunkEvent ce = toChunkEvent(lvl, ch, isNew);
                        if (ce != null) {
                            for (Consumer<ChunkEvent> l : chunkLoadListeners) l.accept(ce);
                        }
                    });
            registerBusEvent(eventBus, addListener, "net.neoforged.neoforge.event.level.ChunkEvent$Unload",
                    ev -> {
                        Object lvl = invokeAny(ev, "getLevel");
                        Object ch = invokeAny(ev, "getChunk");
                        ChunkEvent ce = toChunkEvent(lvl, ch, false);
                        if (ce != null) {
                            for (Consumer<ChunkEvent> l : chunkUnloadListeners) l.accept(ce);
                        }
                    });
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "[StressTestRTP] Failed to register NeoForge callbacks", t);
        }
    }

    private static void registerBusEvent(
            Object eventBus, Method addListener, String eventClassName, Consumer<Object> handler
    ) {
        try {
            Class<?> eventCls = Class.forName(eventClassName);
            addListener.invoke(eventBus, eventCls, handler);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public String getServerVersion() {
        Object srv = minecraftServer;
        if (srv != null) {
            Object v = invokeAny(srv, "getServerVersion");
            if (v != null && !v.toString().isEmpty()) {
                return v.toString();
            }
        }
        return "26.1.2";
    }

    @Override
    public String getPlatform() {
        return "NeoForge";
    }

    @Override
    public PlatformFamily getPlatformFamily() {
        return PlatformFamily.NEOFORGE;
    }

    @Override
    public String getServerName() {
        return "NeoForge";
    }

    @Override
    public List<LoadedPlugin> loadedPlugins() {
        try {
            Class<?> modListCls = Class.forName("net.neoforged.fml.ModList");
            Object modList = modListCls.getMethod("get").invoke(null);
            Object modsObj = invokeAny(modList, "getMods");
            if (modsObj instanceof Collection<?> mods) {
                List<LoadedPlugin> out = new ArrayList<>(mods.size());
                ClassLoader cl = StressTestNeoForgeMod.class.getClassLoader();
                Path configRoot = Path.of("config");
                for (Object modInfo : mods) {
                    if (modInfo == null) continue;
                    String id = String.valueOf(invokeAny(modInfo, "getModId"));
                    Object verObj = invokeAny(modInfo, "getVersion");
                    String ver = verObj != null ? verObj.toString() : "unknown";
                    List<Path> origins = new ArrayList<>();
                    try {
                        Object owningFile = invokeAny(modInfo, "getOwningFile");
                        Object fileObj = owningFile != null ? invokeAny(owningFile, "getFile") : null;
                        Object pathObj = fileObj != null ? invokeAny(fileObj, "getFilePath") : null;
                        if (pathObj instanceof Path p) {
                            origins.add(p);
                        }
                    } catch (Throwable ignored) {
                    }
                    Path primaryJar = origins.stream().filter(Files::isRegularFile).findFirst().orElse(null);
                    out.add(new LoadedPlugin(id, ver, cl, primaryJar, origins, configRoot.resolve(id), true, ""));
                }
                return Collections.unmodifiableList(out);
            }
        } catch (Throwable ignored) {
        }
        return List.of();
    }
}
