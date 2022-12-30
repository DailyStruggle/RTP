package io.github.dailystruggle.rtp.api;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.selection.SelectionAPI;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;

import java.util.HashMap;
import java.util.Map;

public class RTPAPI {
    private static Runnable reloadTask = null;
    private static void reload() {
        TreeCommand baseCommand = RTP.baseCommand;
        if(baseCommand==null) {
            RTP.getInstance().miscAsyncTasks.add(reloadTask);
            return;
        }
        CommandsAPICommand reloadCmd = baseCommand.getCommandLookup().get("reload");
        if(reloadCmd!=null) reloadCmd.onCommand(CommandsAPI.serverId,new HashMap<>(),null);
        reloadTask = null;
    }

    public static boolean addSubCommand(CommandsAPICommand command) {
        if(RTP.baseCommand != null) {
            RTP.baseCommand.addSubCommand(command);
            return true;
        }
        return false;
    }

    public static boolean addShape(Shape<?> shape) {
        Factory<Shape<?>> factory = (Factory<Shape<?>>) RTP.factoryMap.get(RTP.factoryNames.shape);
        if(factory == null) return false;

        if(factory.contains(shape.name)) return true;
        factory.add(shape.name,shape.clone());

        if(reloadTask==null) {
            reloadTask = RTPAPI::reload;
            RTP.getInstance().miscAsyncTasks.add(reloadTask);
        }

        return true;
    }

    public static long loadedLocations(String regionName) {
        if(RTP.getInstance() == null) return 0;
        SelectionAPI selectionAPI = RTP.selectionAPI;
        if(selectionAPI == null) return 0;
        Region region = selectionAPI.getRegion(regionName);
        if(region == null) return 0;
        return region.getPublicQueueLength();
    }
}