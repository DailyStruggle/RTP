package io.github.dailystruggle.rtp.api;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import org.simpleyaml.configuration.MemorySection;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

public class RTPAPI {
    public static boolean addSubCommand(CommandsAPICommand command) {
        if(RTP.baseCommand != null) {
            RTP.baseCommand.addSubCommand(command);
            return true;
        }
        return false;
    }

    public static boolean addShape(Shape<?> shape) {
        RTP rtp = RTP.getInstance();
        if(rtp == null) return false;

        Factory<Shape<?>> factory = (Factory<Shape<?>>) rtp.factoryMap.get(RTP.factoryNames.shape);
        if(factory == null) return false;

        factory.add(shape.name,shape);

        Collection<Region> regions = rtp.selectionAPI.permRegionLookup.values();

        for(Region region : regions) {
            EnumMap<RegionKeys, Object> data = region.getData();
            Object o = data.get(RegionKeys.shape);
            if(o instanceof MemorySection memorySection) {
                Object shapeName = memorySection.get("name", "CIRCLE");
                if(shapeName.toString().equalsIgnoreCase(shape.name)) {
                    final Map<String, Object> shapeMap = memorySection.getMapValues(true);
                    EnumMap<?, Object> shapeData = shape.getData();
                    for(var e : shapeData.entrySet()) {
                        String name = e.getKey().name();
                        if(shapeMap.containsKey(name)) {
                            e.setValue(shapeMap.get(name));
                        }
                        else {
                            Object altName = shape.language_mapping.get(name);
                            if(altName!=null && shapeMap.containsKey(altName)) {
                                e.setValue(shapeMap.get(altName));
                            }
                        }
                    }
                    shape.setData(shapeData);
                    data.put(RegionKeys.shape, shape);
                    region.setData(data);
                }
            }
        }

        return true;
    }
}