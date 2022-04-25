package leafcraft.rtp.bukkit.commands.parameters;

import io.github.dailystruggle.commandsapi.bukkit.BukkitParameter;
import leafcraft.rtp.api.RTPAPI;
import leafcraft.rtp.api.factory.Factory;
import leafcraft.rtp.api.selection.SelectionAPI;
import org.bukkit.command.CommandSender;

import java.util.*;
import java.util.function.BiFunction;

public class ShapeParameter extends BukkitParameter {
    public ShapeParameter(String permission, String description, BiFunction<CommandSender, String, Boolean> isRelevant) {
        super(permission, description, isRelevant);
    }

    @Override
    public Set<String> values() {
        Factory<?> shapeFactory = RTPAPI.getInstance().factoryMap.get(RTPAPI.factoryNames.shape);
        Enumeration<String> listEnum = shapeFactory.list();
        Set<String> res = new HashSet<>();
        while (listEnum.hasMoreElements()) {
            res.add(listEnum.nextElement());
        }
        return res;
    }
}
