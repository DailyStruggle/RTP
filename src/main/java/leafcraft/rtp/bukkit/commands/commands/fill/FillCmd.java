package leafcraft.rtp.bukkit.commands.commands.fill;

import io.github.dailystruggle.commandsapi.bukkit.localCommands.BukkitTreeCommand;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import leafcraft.rtp.api.RTPAPI;
import leafcraft.rtp.api.selection.RegionParams;
import leafcraft.rtp.api.selection.region.Region;
import leafcraft.rtp.bukkit.commands.commands.fill.subcommands.Cancel;
import leafcraft.rtp.bukkit.commands.commands.fill.subcommands.Pause;
import leafcraft.rtp.bukkit.commands.commands.fill.subcommands.Resume;
import leafcraft.rtp.bukkit.commands.commands.fill.subcommands.Start;
import leafcraft.rtp.bukkit.commands.parameters.RegionParameter;
import leafcraft.rtp.bukkit.api.substitutions.BukkitRTPWorld;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class FillCmd extends BukkitTreeCommand {
    public FillCmd(Plugin plugin, CommandsAPICommand parent) {
        super(plugin, parent);

        addSubCommand(new Start(plugin,this));
        addSubCommand(new Pause(plugin,this));
        addSubCommand(new Resume(plugin,this));
        addSubCommand(new Cancel(plugin,this));

        addParameter("region",new RegionParameter("","",(sender, s) -> true));
    }

    @Override
    public boolean onCommand(CommandSender sender, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
        if(nextCommand == null) {
            ((BukkitTreeCommand)commandLookup.get("resume")).onCommand(sender, parameterValues, null);
            return true; //doesn't matter, nextCommand was null
        }

        //if sender put the cart before the horse, just run the next command with the same params and stop there.
        // <- in case anyone asks why people annoy me
        if(parameterValues.containsKey("region")) {
            ((BukkitTreeCommand) nextCommand).onCommand(sender,parameterValues,null);
            return false; //don't run nextCommand
        }
        return true; //run nextCommand
    }

    @Override
    public String name() {
        return "fill";
    }

    @Override
    public String permission() {
        return "rtp.fill";
    }

    @Override
    public String description() {
        return null;
    }

    public static void apply(CommandSender sender,
                             Map<String, List<String>> parameterValues,
                             Predicate<Region> checkRunning,
                             String failMessageLookup,
                             Consumer<Region> action) {
//        List<String> regionNames = parameterValues.get("region");
//        if(regionNames == null) regionNames = new ArrayList<>(1);
//        if(regionNames.size() == 0) {
//            String regionName;
//            World world;
//            if(sender instanceof Player) {
//                world = ((Player)sender).getWorld();
//                Configs.worlds.checkWorldExists(world.getName());
//                regionName = (String) Configs.worlds.getWorldSetting(world.getName(),"region", "default");
//            }
//            else {
//                regionName = "default";
//            }
//
//            regionNames.add(regionName);
//        }
//
//        for(String regionName : regionNames) {
//            Map<String, String> fillCommandArgs = new HashMap<>();
//            fillCommandArgs.put("region", regionName);
//
//            String worldName = (String) Configs.regions.getRegionSetting(regionName, "world", "");
//            if (worldName.equals("")) {
//                String msg = Configs.lang.getLog("badArg", "region:" + regionName);
//                SendMessage.sendMessage(sender, msg);
//                return;
//            }
//
//            RegionParams regionParams = new RegionParams(new BukkitRTPWorld(Objects.requireNonNull(Bukkit.getWorld(worldName))), fillCommandArgs);
//            Region region = null;
//            if (RTPAPI.getInstance().selectionAPI.permRegions.containsKey(regionParams)) {
//                Region foundRegion = RTPAPI.getInstance().selectionAPI.permRegions.get(regionParams);
//                if(foundRegion == null) {
//                    String msg = Configs.lang.getLog("badArg", "region:" + regionName);
//                    SendMessage.sendMessage(sender, msg);
//                    return;
//                }
//            } else {
//                String msg = Configs.lang.getLog("badArg", "region:" + regionName);
//                SendMessage.sendMessage(sender, msg);
//                return;
//            }
//
//            if (checkRunning.test(region)) {
//                String msg = Configs.lang.getLog(failMessageLookup, regionName);
//                SendMessage.sendMessage(sender, msg);
//                return;
//            }
//
//            action.accept(region);
//        }
    }
}
