package io.github.dailystruggle.rtp.fabric;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.fabric.commands.RTPCmdFabric;
import io.github.dailystruggle.rtp.fabric.listeners.FabricPlayerJoin;
import io.github.dailystruggle.rtp.fabric.server.FabricServerAccessor;
import io.github.dailystruggle.rtp.fabric.scheduling.FabricScheduler;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public class RTPFabric implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("rtp");
    private static RTPFabric instance;
    private RTP rtp;

    @Override
    public void onInitialize() {
        instance = this;

        // Initialize API with Fabric implementations
        FabricServerAccessor accessor = new FabricServerAccessor();
        RTP.serverAccessor = accessor;
        RTP.scheduler = new FabricScheduler();

        ServerLifecycleEvents.SERVER_STARTING.register(accessor::setServer);

        // Load RTP core
        rtp = new RTP();

        RTP.baseCommand = new RTPCmdFabric();

        FabricPlayerJoin.register();

        // Setup database and other core systems
        // Note: Similar to BukkitDatabaseHandler, we'll need a way to initialize the database
        // For now, let's keep it simple and just start the core

        RTP.getInstance().startupTasks.execute(Long.MAX_VALUE);

        LOGGER.info("RTP Fabric initialized!");
    }

    public static RTPFabric getInstance() {
        return instance;
    }

    public File getDataDirectory() {
        return FabricLoader.getInstance().getConfigDir().resolve("rtp").toFile();
    }
}
