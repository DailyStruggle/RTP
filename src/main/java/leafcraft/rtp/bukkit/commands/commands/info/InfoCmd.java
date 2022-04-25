package leafcraft.rtp.bukkit.commands.commands.info;

import leafcraft.rtp.api.RTPAPI;
import leafcraft.rtp.api.configuration.ConfigParser;
import leafcraft.rtp.api.configuration.MultiConfigParser;
import leafcraft.rtp.api.configuration.enums.LangKeys;
import leafcraft.rtp.api.configuration.enums.WorldKeys;
import leafcraft.rtp.api.selection.region.Region;
import leafcraft.rtp.bukkit.RTPBukkitPlugin;
import leafcraft.rtp.bukkit.tools.SendMessage;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class InfoCmd implements CommandExecutor {
    private static final Set<String> infoParams = new HashSet<>();
    static {
        infoParams.add("region");
        infoParams.add("world");
    }


    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        ConfigParser<LangKeys> lang = RTPAPI.getInstance().configs.lang;
        if(!sender.hasPermission("rtp.info")) {
            SendMessage.sendMessage(sender, (String) lang.getConfigValue(LangKeys.noPerms,""));
            return true;
        }

        if(args.length == 0) {
            Bukkit.getScheduler().runTaskAsynchronously(RTPBukkitPlugin.getInstance(),()-> {
                SendMessage.sendMessage(sender, (String) lang.getConfigValue(LangKeys.title,""));
                SendMessage.sendMessage(sender, ((String) lang.getConfigValue(LangKeys.chunks,""))
                        .replace("[chunks]",""));
                SendMessage.sendMessage(sender, (String) lang.getConfigValue(LangKeys.worldHeader,""));
                for(World world : Bukkit.getWorlds()) {
                    String msg = ((String)lang.getConfigValue(LangKeys.world,"")).replace("[world]", world.getName());
                    String hover = "/rtp info world:" + world.getName();
                    String click = "/rtp info world:" + world.getName();
                    SendMessage.sendMessage(sender,msg,hover,click);
                }
                SendMessage.sendMessage(sender, (String) lang.getConfigValue(LangKeys.regionHeader,""));
                for(Region region : RTPAPI.getInstance().selectionAPI.permRegions.values()) {
                    String msg = ((String)lang.getConfigValue(LangKeys.region,"")).replace("[region]", region.name);
                    String hover = "/rtp info region:" + region.name;
                    String click = "/rtp info region:" + region.name;
                    SendMessage.sendMessage(sender,msg,hover,click);
                }
            });
            return true;
        }

        Bukkit.getScheduler().runTaskAsynchronously(RTPBukkitPlugin.getInstance(),()-> {
            for (String s : args) {
                int idx = s.indexOf(':');
                String arg = idx > 0 ? s.substring(0, idx) : s;
                if (!infoParams.contains(arg)) continue;
                String val = s.substring(idx+1);

                MultiConfigParser<WorldKeys> worlds = RTPAPI.getInstance().configs.worlds;

                switch (arg.toLowerCase()) {
                    case "world" -> {
                        ConfigParser<WorldKeys> worldsParser = worlds.getParser(val);
                        if(worldsParser == null) return;
                        Map<String, String> tokens = new HashMap<>();
                        tokens.put("[world]", val);
                        tokens.put("[name]", val);
                        tokens.put("[region]", String.valueOf(worldsParser.getConfigValue(WorldKeys.region, "")));
                        tokens.put("[requirePermission]", String.valueOf(worldsParser.getConfigValue( WorldKeys.requirePermission, false)));
                        tokens.put("[override]", String.valueOf(worldsParser.getConfigValue( WorldKeys.override, "")));
                        tokens.put("[nearShape]", String.valueOf(worldsParser.getConfigValue( WorldKeys.nearShape, "CIRCLE")));

                        List<String> msgList = (List<String>) lang.getConfigValue(LangKeys.worldInfo, new ArrayList<>());
                        for (String msg : msgList) {
                            for (Map.Entry<String, String> entry : tokens.entrySet()) {
                                msg = msg.replace(entry.getKey(), entry.getValue());
                            }
                            SendMessage.sendMessage(sender, msg);
                        }
                    }
                    case "region" -> {
                        Region region = null;
                        for (Region teleportRegion : RTPAPI.getInstance().selectionAPI.permRegions.values()) {
                            if (teleportRegion.name.equals(val)) {
                                region = teleportRegion;
                                break;
                            }
                        }
                        if (region == null) continue;

                        Map<String, String> tokens = new HashMap<>();
                        //todo
//                        tokens.put("[region]", val);
//                        tokens.put("[world]", Objects.requireNonNull(region.world).getName());
//                        tokens.put("[shape]", region.shape.name());
//                        tokens.put("[mode]", region.mode.name());
//                        tokens.put("[radius]", String.valueOf(region.r));
//                        tokens.put("[centerRadius]", String.valueOf(region.cr));
//                        tokens.put("[centerX]", String.valueOf(region.cx));
//                        tokens.put("[centerZ]", String.valueOf(region.cz));
//                        tokens.put("[minY]", String.valueOf(region.minY));
//                        tokens.put("[maxY]", String.valueOf(region.maxY));
//                        tokens.put("[weight]", String.valueOf(region.weight));
//                        tokens.put("[requireSkyLight]", String.valueOf(region.requireSkyLight));
//                        tokens.put("[requirePermission]", String.valueOf(Configs.regions.getRegionSetting(val, "requirePermission", false)));
//                        tokens.put("[worldBorderOverride]", String.valueOf(Configs.regions.getRegionSetting(val, "worldBorderOverride", false)));
//                        tokens.put("[uniquePlacements]", String.valueOf(region.uniquePlacements));
//                        tokens.put("[expand]", String.valueOf(region.expand));
//                        tokens.put("[queueLen]", String.valueOf(Configs.regions.getRegionSetting(val, "queueLen", -1)));
//                        tokens.put("[queued]", String.valueOf(region.getPublicQueueLength()));

//                        List<String> msgList = Configs.lang.getLogList("regionInfo");
//                        for (String msg : msgList) {
//                            for (Map.Entry<String, String> entry : tokens.entrySet()) {
//                                msg = msg.replace(entry.getKey(), entry.getValue());
//                            }
//                            SendMessage.sendMessage(sender, msg);
//                        }
                    }
                }
            }
        });

        return true;
    }
}
