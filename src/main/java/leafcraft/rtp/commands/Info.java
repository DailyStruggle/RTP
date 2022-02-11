package leafcraft.rtp.commands;

import leafcraft.rtp.RTP;
import leafcraft.rtp.tools.SendMessage;
import leafcraft.rtp.tools.configuration.Configs;
import leafcraft.rtp.tools.selection.TeleportRegion;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class Info implements CommandExecutor {
    private static final Set<String> infoParams = new HashSet<>();
    static {
        infoParams.add("region");
        infoParams.add("world");
    }


    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        Configs configs = RTP.getConfigs();
        if(!sender.hasPermission("rtp.info")) {
            SendMessage.sendMessage(sender, configs.lang.getLog("noPerms"));
            return true;
        }

        if(args.length == 0) {
            Bukkit.getScheduler().runTaskAsynchronously(RTP.getPlugin(),()-> {
                SendMessage.sendMessage(sender,configs.lang.getLog("title"));
                SendMessage.sendMessage(sender,configs.lang.getLog("chunks")
                        .replace("[chunks]",String.valueOf(RTP.getCache().forceLoadedChunks.size())));
                SendMessage.sendMessage(sender,configs.lang.getLog("worldHeader"));
                for(World world : Bukkit.getWorlds()) {
                    String msg = configs.lang.getLog("world").replace("[world]", world.getName());
                    String hover = "/rtp info world:" + world.getName();
                    String click = "/rtp info world:" + world.getName();
                    SendMessage.sendMessage(sender,msg,hover,click);
                }
                SendMessage.sendMessage(sender,configs.lang.getLog("regionHeader"));
                for(TeleportRegion region : RTP.getCache().permRegions.values()) {
                    String msg = configs.lang.getLog("region").replace("[region]",region.name);
                    String hover = "/rtp info region:" + region.name;
                    String click = "/rtp info region:" + region.name;
                    SendMessage.sendMessage(sender,msg,hover,click);
                }
            });
            return true;
        }

        Bukkit.getScheduler().runTaskAsynchronously(RTP.getPlugin(),()-> {
            for (String s : args) {
                int idx = s.indexOf(':');
                String arg = idx > 0 ? s.substring(0, idx) : s;
                if (!infoParams.contains(arg)) continue;
                String val = s.substring(idx+1);

                switch (arg.toLowerCase()) {
                    case "world" -> {
                        Map<String, String> tokens = new HashMap<>();
                        tokens.put("[world]", val);
                        tokens.put("[name]", (String) configs.worlds.getWorldSetting(val, "name", ""));
                        tokens.put("[region]", (String) configs.worlds.getWorldSetting(val, "region", ""));
                        tokens.put("[requirePermission]", String.valueOf(configs.worlds.getWorldSetting(val, "requirePermission", false)));
                        tokens.put("[override]", String.valueOf(configs.worlds.getWorldSetting(val, "override", "")));
                        tokens.put("[nearShape]", String.valueOf(configs.worlds.getWorldSetting(val, "nearShape", "CIRCLE")));
                        tokens.put("[nearRadius]", String.valueOf(configs.worlds.getWorldSetting(val, "nearRadius", -1)));
                        tokens.put("[nearCenterRadius]", String.valueOf(configs.worlds.getWorldSetting(val, "override", -1)));
                        tokens.put("[nearMinY]", String.valueOf(configs.worlds.getWorldSetting(val, "nearMinY", -1)));
                        tokens.put("[nearMaxY]", String.valueOf(configs.worlds.getWorldSetting(val, "nearMaxY", -1)));

                        List<String> msgList = configs.lang.getLogList("worldInfo");
                        for (String msg : msgList) {
                            for (Map.Entry<String, String> entry : tokens.entrySet()) {
                                msg = msg.replace(entry.getKey(), entry.getValue());
                            }
                            SendMessage.sendMessage(sender, msg);
                        }
                    }
                    case "region" -> {
                        TeleportRegion region = null;
                        for (TeleportRegion teleportRegion : RTP.getCache().permRegions.values()) {
                            if (teleportRegion.name.equals(val)) {
                                region = teleportRegion;
                                break;
                            }
                        }
                        if (region == null) continue;

                        Map<String, String> tokens = new HashMap<>();
                        tokens.put("[region]", val);
                        tokens.put("[world]", Objects.requireNonNull(region.world).getName());
                        tokens.put("[shape]", region.shape.name());
                        tokens.put("[mode]", region.mode.name());
                        tokens.put("[radius]", String.valueOf(region.r));
                        tokens.put("[centerRadius]", String.valueOf(region.cr));
                        tokens.put("[centerX]", String.valueOf(region.cx));
                        tokens.put("[centerZ]", String.valueOf(region.cz));
                        tokens.put("[minY]", String.valueOf(region.minY));
                        tokens.put("[maxY]", String.valueOf(region.maxY));
                        tokens.put("[weight]", String.valueOf(region.weight));
                        tokens.put("[requireSkyLight]", String.valueOf(region.requireSkyLight));
                        tokens.put("[requirePermission]", String.valueOf(configs.regions.getRegionSetting(val, "requirePermission", false)));
                        tokens.put("[worldBorderOverride]", String.valueOf(configs.regions.getRegionSetting(val, "worldBorderOverride", false)));
                        tokens.put("[uniquePlacements]", String.valueOf(region.uniquePlacements));
                        tokens.put("[expand]", String.valueOf(region.expand));
                        tokens.put("[queueLen]", String.valueOf(configs.regions.getRegionSetting(val, "queueLen", -1)));
                        tokens.put("[queued]", String.valueOf(region.getPublicQueueLength()));

                        List<String> msgList = configs.lang.getLogList("regionInfo");
                        for (String msg : msgList) {
                            for (Map.Entry<String, String> entry : tokens.entrySet()) {
                                msg = msg.replace(entry.getKey(), entry.getValue());
                            }
                            SendMessage.sendMessage(sender, msg);
                        }
                    }
                }
            }
        });

        return true;
    }
}
