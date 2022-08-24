package io.github.dailystruggle.rtp.common.commands.reload;

import io.github.dailystruggle.commandsapi.bukkit.localCommands.BukkitTreeCommand;
import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.rtp.bukkit.commands.BukkitBaseRTPCmd;
import io.github.dailystruggle.rtp.bukkit.server.substitutions.BukkitRTPCommandSender;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmd;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.LangKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.WorldKeys;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import io.github.dailystruggle.rtp.common.serverSide.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPCommandSender;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import io.github.dailystruggle.rtp.common.tasks.RTPTeleportCancel;
import org.apache.commons.lang3.StringUtils;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import org.simpleyaml.configuration.MemorySection;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class ReloadCmd extends BaseRTPCmdImpl {

    public ReloadCmd(@Nullable CommandsAPICommand parent) {
        super(parent);

        RTP.getInstance().miscAsyncTasks.add(new RTPRunnable(this::addCommands,5));
    }

    void addCommands() {
        final Configs configs = RTP.getInstance().configs;
        for (ConfigParser<?> value : configs.configParserMap.values()) {
            String name = value.name.replace(".yml","");
            if(getCommandLookup().containsKey(name)) continue;
            addSubCommand(new SubReloadCmd<>(this,value.name,"rtp.reload","reload only " + value.name, value.myClass));
        }

        for (Map.Entry<Class<?>, MultiConfigParser<?>> e : configs.multiConfigParserMap.entrySet()) {
            MultiConfigParser<? extends Enum<?>> value = e.getValue();
            if(getCommandLookup().containsKey(value.name)) continue;
            addSubCommand(new SubReloadCmd<>(this,value.name,"rtp.reload","reload only " + value.name, value.myClass));
        }
    }

    @Override
    public String name() {
        return "reload";
    }

    @Override
    public String permission() {
        return "rtp.reload";
    }

    @Override
    public String description() {
        return "reload config files";
    }

    @Override
    public boolean onCommand(UUID senderId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
        addCommands();

        RTP.serverAccessor.reset();

        final RTP instance = RTP.getInstance();
        instance.setupTeleportPipeline.clear();
        instance.loadChunksPipeline.clear();
        instance.teleportPipeline.clear();
        instance.chunkCleanupPipeline.execute(Long.MAX_VALUE);
        instance.selectionAPI.permRegionLookup.values().forEach(Region::shutDown);
        instance.selectionAPI.tempRegions.values().forEach(Region::shutDown);
        instance.selectionAPI.tempRegions.clear();
        instance.latestTeleportData.forEach((uuid, data) -> {
            if(!data.completed) new RTPTeleportCancel(uuid).run();
        });
        instance.processingPlayers.clear();

        RTP.serverAccessor.getRTPWorlds().forEach(RTPWorld::forgetChunks);

        if(nextCommand!=null) return true;

        ConfigParser<LangKeys> lang = (ConfigParser<LangKeys>) RTP.getInstance().configs.getParser(LangKeys.class);
        if(lang != null) {
            String msg = String.valueOf(lang.getConfigValue(LangKeys.reloading,""));
            if(msg!=null) msg = StringUtils.replace(msg,"[filename]", "configs");
            RTP.serverAccessor.sendMessage(CommandsAPI.serverId, senderId,msg);
        }

        RTP.getInstance().configs.reload();

        return true;
    }
}
